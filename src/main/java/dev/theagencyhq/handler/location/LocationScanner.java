/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.location;

import module java.base;

import dev.theagencyhq.handler.config.HandlerConfig;

/**
 * Finds every Location under the configured start directory. Symbolic links are never followed, which also removes any
 * possibility of a traversal cycle.
 *
 * @author Brian Pontarelli
 */
public class LocationScanner {
  public static final String MARKER_FILENAME = "agent-location.json";
  public static final int MAXIMUM_DEPTH = 25;
  private static final System.Logger LOG = System.getLogger(LocationScanner.class.getName());

  private final List<PathMatcher> excludes;
  private final Path startDirectory;
  private boolean startDirectoryUnreadable;

  public LocationScanner(HandlerConfig config) {
    this.startDirectory = config.startDirectoryPath();
    this.excludes = config.excludeDirectories()
                          .stream()
                          .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                          .toList();
  }

  public List<Location> scan() {
    long start = System.currentTimeMillis();
    List<Location> locations = new ArrayList<>();
    startDirectoryUnreadable = false;
    scan(startDirectory, 0, locations);
    long milliseconds = System.currentTimeMillis() - start;
    LOG.log(System.Logger.Level.DEBUG, "Scanned for Locations in [{0}]ms and found [{1}]", milliseconds, locations.size());

    return List.copyOf(locations);
  }

  /**
   * @return True if the start directory itself was unreadable during the last {@link #scan()}, meaning the result was
   *     an empty or incomplete list rather than a true "nothing exists" — never true before the first scan.
   */
  public boolean startDirectoryUnreadable() {
    return startDirectoryUnreadable;
  }

  private boolean excluded(Path directory) {
    Path name = directory.getFileName();
    for (PathMatcher matcher : excludes) {
      if (matcher.matches(name)) {
        return true;
      }
    }

    return false;
  }

  private Optional<LocationMarker> marker(Path markerFile) {
    LocationMarker marker;
    try {
      marker = LocationMarker.fromJSON(Files.readAllBytes(markerFile));
    } catch (IOException | RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "Unable to parse the Location marker [" + markerFile + "], skipping it", e);
      return Optional.empty();
    }

    if (marker.organizationId().isEmpty()) {
      LOG.log(System.Logger.Level.ERROR, "Location marker [{0}] has no organizationId, skipping it", markerFile);
      return Optional.empty();
    }

    if (marker.majorVersion() != LocationMarker.SUPPORTED_MAJOR_VERSION) {
      LOG.log(System.Logger.Level.ERROR, "Location marker [{0}] has unsupported format version [{1}], skipping it",
              markerFile, marker.version());
      return Optional.empty();
    }

    return Optional.of(marker);
  }

  private void scan(Path directory, int depth, List<Location> locations) {
    if (depth > MAXIMUM_DEPTH) {
      LOG.log(System.Logger.Level.DEBUG, "Depth cap reached at [{0}]", directory);
      return;
    }

    Path markerFile = directory.resolve(MARKER_FILENAME);
    if (Files.isRegularFile(markerFile, LinkOption.NOFOLLOW_LINKS)) {
      marker(markerFile).ifPresent(marker -> locations.add(Location.from(directory, marker)));
      return;     // a Location owns its whole subtree
    }

    try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
      for (Path child : children) {
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || excluded(child)) {
          continue;
        }

        scan(child, depth + 1, locations);
      }
    } catch (IOException e) {
      if (directory.equals(startDirectory)) {
        // DEBUG here would hide this behind the default INFO level, and an empty result is indistinguishable from
        // "no Locations exist" - a caller purging on that assumption needs to know the scan itself failed
        startDirectoryUnreadable = true;
        LOG.log(System.Logger.Level.WARNING, "Unable to read the start directory [" + directory + "]; the scan is"
                                             + " incomplete", e);
      } else {
        LOG.log(System.Logger.Level.DEBUG, "Skipping unreadable directory [" + directory + "]", e);
      }
    }
  }
}

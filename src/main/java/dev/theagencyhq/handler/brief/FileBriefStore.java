/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;

/**
 * The on-disk Brief store, laid out as {@code {storeRoot}/{organizationId}/{version}/brief.json}. No version is ever
 * pruned.
 *
 * @author Brian Pontarelli
 */
public class FileBriefStore implements BriefStore {
  private static final String DOCUMENT = "brief.json";
  private static final String INCOMING_DIRECTORY = "incoming";
  private static final System.Logger LOG = System.getLogger(FileBriefStore.class.getName());
  private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");
  private static final String REVOKED_MARKER = ".revoked";
  private static final Duration TEMPORARY_MINIMUM_AGE = Duration.ofMinutes(5);
  private static final String TEMPORARY_PREFIX = DOCUMENT + ".tmp-";

  private final Path incoming;
  private final Path storeRoot;

  public FileBriefStore(Path storeRoot) throws IOException {
    this.storeRoot = storeRoot;
    this.incoming = storeRoot.resolve(INCOMING_DIRECTORY);
    Files.createDirectories(incoming);
  }

  @Override
  public List<StoredBrief> allCurrent() {
    return organizationIds().stream()
                            .filter(id -> !revoked(id))
                            .map(this::latest)
                            .flatMap(Optional::stream)
                            .sorted(Comparator.comparing(StoredBrief::organizationId))
                            .toList();
  }

  /**
   * Deletes temporary documents orphaned by a store that died between the write and the atomic move. They are invisible
   * to {@link #latest} — it reads only {@code brief.json} — so this is litter rather than a correctness problem, but no
   * version is ever pruned, so nothing else would ever remove it.
   * <p>
   * All incoming files go to the same location in the Store, which makes cleanup a breeze. We just need to ensure we
   * don't clobber a file that someone is currently writing via the CLI's `handler sync`. To preven that, we check that
   * the temp file is old, which means it's officially junk since the `handler sync` runs in seconds, not minutes.
   */
  @Override
  public void cleanup() {
    Instant cutoff = Instant.now().minus(TEMPORARY_MINIMUM_AGE);
    int removed = 0;
    try (var tempFiles = Files.newDirectoryStream(incoming)) {
      for (var tempFile : tempFiles) {
        if (!Files.isRegularFile(tempFile) || !tempFile.getFileName().toString().startsWith(TEMPORARY_PREFIX)
            || Files.getLastModifiedTime(tempFile).toInstant().isAfter(cutoff)) {
          continue;
        }

        if (Files.deleteIfExists(tempFile)) {
          removed++;
        }
      }
    } catch (IOException e) {
      LOG.log(System.Logger.Level.DEBUG, "Unable to sweep orphaned temporary documents from [" + incoming + "]", e);
    }

    if (removed > 0) {
      LOG.log(System.Logger.Level.INFO, "Removed [{0}] orphaned temporary documents from [{1}]", removed, incoming);
    }
  }

  @Override
  public Optional<StoredBrief> latest(String organizationId) {
    Path organizationDirectory = storeRoot.resolve(organizationId);
    if (!Files.isDirectory(organizationDirectory)) {
      return Optional.empty();
    }

    List<Integer> versions = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(organizationDirectory)) {
      for (Path child : children) {
        if (!Files.isDirectory(child)) {
          continue;
        }

        try {
          versions.add(Integer.parseInt(child.getFileName().toString()));
        } catch (NumberFormatException ignored) {
          // Non-numeric directory names are not versions
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to list the store directory [" + organizationDirectory + "]", e);
    }

    versions.sort(Comparator.reverseOrder());
    for (int version : versions) {
      Path document = organizationDirectory.resolve(Integer.toString(version)).resolve(DOCUMENT);
      Optional<StoredBrief> candidate = read(document, organizationId, version);
      if (candidate.isPresent()) {
        return candidate;
      }
    }

    return Optional.empty();
  }

  @Override
  public void markRevoked(String organizationId) {
    Path marker = storeRoot.resolve(organizationId).resolve(REVOKED_MARKER);
    try {
      Files.createDirectories(marker.getParent());
      if (Files.exists(marker)) {
        return;
      }

      Files.createFile(marker);
      LOG.log(System.Logger.Level.INFO, "Marked Organization [{0}] revoked", organizationId);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to mark Organization [" + organizationId + "] revoked", e);
    }
  }

  @Override
  public Set<String> organizationIds() {
    if (!Files.isDirectory(storeRoot)) {
      return Set.of();
    }

    try (DirectoryStream<Path> children = Files.newDirectoryStream(storeRoot)) {
      Set<String> ids = new TreeSet<>();
      for (Path child : children) {
        if (Files.isDirectory(child) && !child.endsWith(INCOMING_DIRECTORY)) {
          ids.add(child.getFileName().toString());
        }
      }

      return ids;
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to list the store root [" + storeRoot + "]", e);
    }
  }

  @Override
  public void purge(String organizationId) {
    Path organizationDirectory = storeRoot.resolve(organizationId);
    if (!Files.exists(organizationDirectory)) {
      return;
    }

    try (Stream<Path> paths = Files.walk(organizationDirectory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }

      LOG.log(System.Logger.Level.INFO, "Purged Organization [{0}] from the store", organizationId);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to purge Organization [" + organizationId + "]", e);
    }
  }

  @Override
  public boolean revoked(String organizationId) {
    return Files.exists(storeRoot.resolve(organizationId).resolve(REVOKED_MARKER));
  }

  @Override
  public void store(Brief brief) {
    // Take out the trash first
    try {
      cleanup();
    } catch (Exception _) {
      // This is completely ignored because it should not impact the ability to store the new brief
    }

    String organizationId = brief.organization().id();
    Path organizationDirectory = storeRoot.resolve(organizationId);
    Path versionDirectory = organizationDirectory.resolve(Integer.toString(brief.version()));

    Path temporary = null;
    try {
      // Create the final location ahead of time to ensure it is always there
      Files.createDirectories(versionDirectory);

      // Write the new brief to the incoming directory
      temporary = incoming.resolve(TEMPORARY_PREFIX + UUID.randomUUID());
      Files.write(temporary, brief.rawBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
      Files.setPosixFilePermissions(temporary, OWNER_READ_WRITE);

      // Move it into place
      Files.move(temporary, versionDirectory.resolve(DOCUMENT), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      LOG.log(System.Logger.Level.INFO, "Stored Organization [{0}] version [{1}]", organizationId, brief.version());
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to store Organization [" + organizationId + "] version [" + brief.version() + "]", e);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException _) {
          // This temp file will hopefully be deleted during the next cleanup() call
        }
      }
    }
  }

  private Optional<StoredBrief> read(Path document, String organizationId, int version) {
    if (!Files.isRegularFile(document)) {
      return Optional.empty();      // created but not yet populated - the previous version stays live
    }

    try {
      Brief brief = Brief.fromJSON(Files.readAllBytes(document));
      if (!brief.organization().id().equals(organizationId) || brief.version() != version) {
        LOG.log(System.Logger.Level.ERROR, "Store document [{0}] disagrees with its path and was skipped", document);
        return Optional.empty();
      }

      return Optional.of(new StoredBrief(brief, document));
    } catch (IOException | RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "Unable to parse store document [" + document + "], skipping it", e);
      return Optional.empty();
    }
  }
}

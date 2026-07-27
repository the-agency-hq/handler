/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;
import module org.lattejava.version;

/**
 * The on-disk {@code .handler-manifest}. Reads itself on construction, creating the file with only the version line
 * when it is absent.
 *
 * @author Brian Pontarelli
 */
public class FileManifest implements Manifest {
  private final List<Entry> entries = new ArrayList<>();
  private final Path manifestFile;

  public FileManifest(Path manifestFile) {
    this.manifestFile = manifestFile;
    // A zero-length manifest is indistinguishable from one the Handler never finished creating - restart it
    if (Files.isRegularFile(manifestFile) && size(manifestFile) > 0) {
      entries.addAll(peek(manifestFile));
    } else {
      rewrite(List.of());
    }
  }

  /**
   * Reads a manifest's entries without creating or modifying anything, so {@code handler status} stays a pure read.
   *
   * @param manifestFile The manifest to read.
   * @return Its entries in creation order, or an empty list if it does not exist or is empty.
   * @throws Manifest.UnsupportedManifestException If the format major version is unknown.
   */
  public static List<Entry> peek(Path manifestFile) {
    if (!Files.isRegularFile(manifestFile)) {
      return List.of();
    }

    List<String> lines;
    try {
      lines = Files.readAllLines(manifestFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read the manifest [" + manifestFile + "]", e);
    }

    if (lines.isEmpty()) {
      return List.of();
    }

    try {
      Version version = new Version(lines.getFirst().trim());
      if (!version.equals(FORMAT_VERSION)) {
        throw new UnsupportedManifestException("Manifest [" + manifestFile + "] has unsupported format version [" + version + "]");
      }
    } catch (VersionException e) {
      throw new UnsupportedManifestException("Manifest [" + manifestFile + "] has an invalid SemVer format version [" + lines.getFirst().trim() + "]");
    }

    List<Entry> entries = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      if (!line.isBlank()) {
        entries.add(Entry.parse(line.strip()));
      }
    }

    return List.copyOf(entries);
  }

  @Override
  public void append(Entry entry) {
    try (FileChannel channel = FileChannel.open(manifestFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.SYNC)) {
      ByteBuffer bytes = ByteBuffer.wrap((entry.line() + "\n").getBytes(StandardCharsets.UTF_8));
      int failures = 0;
      while (bytes.hasRemaining()) {
        if (channel.write(bytes) == 0) {
          failures++;
        }
        if (failures > 5) {
          throw new IllegalStateException("Unable to append to the manifest [" + manifestFile + "]. We tried to write 5 times without success. The file is likely corrupt.");
        }
      }

      channel.force(true);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to append to the manifest [" + manifestFile + "]", e);
    }

    entries.add(entry);
  }

  @Override
  public void clear() {
    rewrite(List.of());
    entries.clear();
  }

  @Override
  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  @Override
  public void reset(List<Entry> entries) {
    rewrite(entries);
    this.entries.clear();
    this.entries.addAll(entries);
  }

  private void rewrite(List<Entry> retained) {
    StringBuilder content = new StringBuilder(FORMAT_VERSION.toString()).append('\n');
    for (Entry entry : retained) {
      content.append(entry.line()).append('\n');
    }

    try (FileChannel channel = FileChannel.open(manifestFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                                                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {
      channel.write(ByteBuffer.wrap(content.toString().getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to write the manifest [" + manifestFile + "]", e);
    }
  }

  private long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      // Must NOT fall back to 0. A stat failure on an existing file would make the constructor treat a live manifest
      // as zero-length and truncate it, turning the manifest into a subset of what is on disk — the one thing this
      // class exists to prevent. Failing loudly makes the Location report FAILED and retry next cycle instead.
      throw new UncheckedIOException("Unable to read the size of the manifest [" + file + "]", e);
    }
  }
}

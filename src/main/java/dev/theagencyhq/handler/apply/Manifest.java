/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;
import module org.lattejava.version;

/**
 * The record of everything the Handler created inside one Location. Entries are held in creation order, so reverse
 * order is always a safe teardown order — a directory never precedes its own contents in reverse.
 *
 * <p>Every mutation reaches disk before returning. That is what makes a crash mid-apply recoverable: the manifest
 * always describes a superset of what exists, never a subset.
 *
 * @author Brian Pontarelli
 */
public interface Manifest {
  String FILENAME = ".handler-manifest";
  Version FORMAT_VERSION = new Version("0.1.0");

  /**
   * The Location-root directory every in-flight write is staged in. It is a subtree of the Location, so {@code
   * ATOMIC_MOVE} out of it into any planned path is always a same-filesystem rename.
   */
  String STAGING_DIRECTORY = ".handler-tmp";

  void append(Entry entry);

  void clear();

  List<Entry> entries();

  /**
   * Replaces the manifest's contents with the given entries in a single flushed write. Unlike clear-then-append this
   * leaves no window in which the manifest is a subset of what exists on disk.
   *
   * @param entries The entries to retain, in creation order.
   */
  void reset(List<Entry> entries);

  record Entry(Path path, boolean directory) {
    /**
     * @return The manifest line for this entry. Directories carry a trailing slash; files do not.
     */
    public String line() {
      return directory ? path + "/" : path.toString();
    }

    static Entry parse(String line) {
      boolean directory = line.endsWith("/");
      return new Entry(Path.of(directory ? line.substring(0, line.length() - 1) : line), directory);
    }
  }

  class UnsupportedManifestException extends RuntimeException {
    public UnsupportedManifestException(String message) {
      super(message);
    }
  }
}

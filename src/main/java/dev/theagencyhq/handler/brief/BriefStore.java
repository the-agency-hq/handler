/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;

/**
 * The immutable, never-pruned local store of Briefs. This is the only handoff between the receive and distribute tasks,
 * so every implementation must make {@link #store} atomic and {@link #latest} skip incomplete versions.
 *
 * @author Brian Pontarelli
 */
public interface BriefStore {
  /**
   * @return The latest Brief for every non-revoked Organization in the store.
   */
  List<StoredBrief> allCurrent();

  /**
   * Cleans up any old files or directories that are no longer needed. This is run during each store operation, but can
   * also be run separately to perform the cleanup as well.
   */
  void cleanup();

  /**
   * @param organizationId The Organization id.
   * @return The highest complete version for the Organization, or empty if it has none.
   */
  Optional<StoredBrief> latest(String organizationId);

  void markRevoked(String organizationId);

  Set<String> organizationIds();

  void purge(String organizationId);

  boolean revoked(String organizationId);

  void store(Brief brief);
}

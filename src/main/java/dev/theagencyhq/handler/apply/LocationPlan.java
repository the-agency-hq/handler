/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;

/**
 * What one Location should contain from one Organization. An empty plan is valid and means "nothing" — it drives a
 * pure teardown, which is both the revocation path and what happens when Mission Type filtering excludes everything.
 *
 * @author Brian Pontarelli
 */
public record LocationPlan(List<PlannedFile> files, SequencedSet<Path> directories) {
  public static final LocationPlan EMPTY = new LocationPlan(List.of(), new LinkedHashSet<>());

  public LocationPlan {
    // EMPTY is a shared constant; without this a caller could add to its directories and poison it for the whole JVM
    files = List.copyOf(files);
    directories = Collections.unmodifiableSequencedSet(new LinkedHashSet<>(directories));
  }

  public boolean isEmpty() {
    return files.isEmpty();
  }
}

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;

/**
 * A Brief read back out of the store, paired with the document it came from.
 *
 * @author Brian Pontarelli
 */
public record StoredBrief(Brief brief, Path path) {
  public String organizationId() {
    return brief.organization().id();
  }

  public int version() {
    return brief.version();
  }
}

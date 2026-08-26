/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.state;

/**
 * What the last distribute cycle did at one Location, as recorded in the state file.
 *
 * @author Brian Pontarelli
 */
public enum LocationStatus {
  /**
   * The Location was skipped for conflicts, its Brief could not be planned, or applying it failed.
   */
  ERROR,

  /**
   * The Location was applied, was already up to date, or had no Brief to apply.
   */
  SUCCESS
}

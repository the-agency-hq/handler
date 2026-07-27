/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

/**
 * The outcome of applying one plan to one Location.
 *
 * @author Brian Pontarelli
 */
public enum ApplyResult {
  APPLIED,
  FAILED,
  SKIPPED_CONFLICT,
  UNCHANGED
}

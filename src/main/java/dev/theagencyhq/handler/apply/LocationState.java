/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

/**
 * The state of a Location as computed by a read-only inspection.
 *
 * @author Brian Pontarelli
 */
public enum LocationState {
  CHANGED,
  CONFLICT,
  UNCHANGED,
  UNREADABLE
}

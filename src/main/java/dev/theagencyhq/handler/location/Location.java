/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.location;

import module java.base;

/**
 * A discovered Location — a directory containing an {@code agent-location.json} marker. A Location owns its whole
 * subtree.
 *
 * @author Brian Pontarelli
 */
public record Location(Path root, String organizationId, List<String> missionTypes) {
  public static Location from(Path root, LocationMarker marker) {
    return new Location(root, marker.organizationId(), marker.missionTypes());
  }
}

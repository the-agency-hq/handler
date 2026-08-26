/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.state;

import module java.base;
import module dev.theagencyhq.handler;
import module org.lattejava.json;

/**
 * One Location as the last distribute cycle found it, and what that cycle did there.
 *
 * @param root           The Location directory.
 * @param organizationId The Organization from the Location's marker.
 * @param missionTypes   The Mission Types from the marker, where empty means all.
 * @param status         What the cycle did.
 * @param message        Why the status is {@link LocationStatus#ERROR}, or null on success.
 * @author Brian Pontarelli
 */
@JSON
public record LocationEntry(String root, String organizationId, List<String> missionTypes, LocationStatus status,
                            String message) {
  public LocationEntry {
    root = root == null ? "" : root;
    organizationId = organizationId == null ? "" : organizationId;
    missionTypes = missionTypes == null ? List.of() : List.copyOf(missionTypes);
  }

  public static LocationEntry of(Location location, LocationStatus status, String message) {
    return new LocationEntry(location.root().toString(), location.organizationId(), location.missionTypes(), status,
                             message);
  }

  /**
   * @return The Location this entry recorded, so the CLI can plan and inspect it without re-reading its marker.
   */
  public Location toLocation() {
    return new Location(Path.of(root), organizationId, missionTypes);
  }
}

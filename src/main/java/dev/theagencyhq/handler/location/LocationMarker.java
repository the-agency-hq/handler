/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.location;

import module java.base;
import module org.lattejava.json;
import module org.lattejava.version;

import dev.theagencyhq.handler.location.internal.LocationMarkerJSON;

/**
 * The contents of an {@code agent-location.json} marker. {@code version} is the marker file's format version, not the
 * Brief version.
 *
 * @author Brian Pontarelli
 */
@JSON
public record LocationMarker(@JSONField(asString = true) Version version, String organizationId,
                             List<String> missionTypes) {
  public static final int SUPPORTED_MAJOR_VERSION = 1;

  public LocationMarker {
    organizationId = organizationId == null ? "" : organizationId.trim();
    missionTypes = missionTypes == null ? List.of()
                                       : missionTypes.stream().map(t -> t.trim().toLowerCase(Locale.ROOT)).toList();
  }

  public static LocationMarker fromJSON(byte[] json) {
    return LocationMarkerJSON.fromJSON(json);
  }

  /**
   * @return The major component of the SemVer format version, or -1 when the marker carried no version at all. An
   *     unparseable version never reaches here — {@code asString} deserialization rejects it, and
   *     {@code LocationScanner} skips the whole marker.
   */
  public int majorVersion() {
    return version == null ? -1 : version.major();
  }

  /**
   * @return This marker as indented JSON, the form {@code handler init} writes to disk so the file is pleasant to read
   *     and diff.
   */
  public String toPrettyString() {
    return LocationMarkerJSON.toPrettyString(this);
  }
}

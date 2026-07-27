/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.agency.internal.BriefingRequestJSON;

/**
 * The {@code POST /api/v1/briefing} request body.
 *
 * @author Brian Pontarelli
 */
@JSON
public record BriefingRequest(List<CurrentVersion> currentVersions) {
  public BriefingRequest {
    currentVersions = currentVersions == null ? List.of() : currentVersions;
  }

  public byte[] toJSONBytes() {
    return BriefingRequestJSON.toJSONBytes(this);
  }
}

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.agency.internal.BriefingResponseJSON;
import dev.theagencyhq.handler.brief.Brief;

/**
 * The {@code 200} response body. {@code organizationIds} is the complete entitled set, not a delta — that is what
 * makes revocation self-healing without a separate event.
 *
 * @author Brian Pontarelli
 */
@JSON
public record BriefingResponse(List<String> organizationIds, List<Brief> briefs) {
  public BriefingResponse {
    organizationIds = organizationIds == null ? List.of() : organizationIds.stream().map(String::trim).toList();
    briefs = briefs == null ? List.of() : briefs;
  }

  public static BriefingResponse fromJSON(byte[] json) {
    return BriefingResponseJSON.fromJSON(json);
  }
}

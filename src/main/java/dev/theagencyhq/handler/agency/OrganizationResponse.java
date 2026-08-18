/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.agency.internal.OrganizationResponseJSON;
import dev.theagencyhq.handler.brief.Organization;

/**
 * The {@code GET /api/v1/organization} response body: every Organization the caller has access to.
 *
 * @author Brian Pontarelli
 */
@JSON
public record OrganizationResponse(List<Organization> organizations) {
  public OrganizationResponse {
    organizations = organizations == null ? List.of() : organizations;
  }

  public static OrganizationResponse fromJSON(byte[] json) {
    return OrganizationResponseJSON.fromJSON(json);
  }
}

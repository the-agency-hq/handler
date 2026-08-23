/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The outcome of one organization-list request, sealed so no caller can forget a case.
 *
 * @author Brian Pontarelli
 */
public sealed interface OrganizationResult {
  record Failed(String reason) implements OrganizationResult {
  }

  record Loaded(List<Organization> organizations) implements OrganizationResult {
  }
}

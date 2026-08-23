/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The outcome of one briefing request, sealed so no caller can forget a case.
 *
 * @author Brian Pontarelli
 */
public sealed interface BriefingResult {
  record Failed(String reason, boolean authenticationFailure) implements BriefingResult {
  }

  record Forbidden() implements BriefingResult {
  }

  record NotModified() implements BriefingResult {
  }

  record Updated(List<String> organizationIds, List<Brief> briefs) implements BriefingResult {
  }
}

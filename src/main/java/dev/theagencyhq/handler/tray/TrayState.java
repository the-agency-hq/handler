/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tray;

import dev.theagencyhq.handler.agency.BriefingResult;

/**
 * The three states the system tray icon can report.
 *
 * @author Brian Pontarelli
 */
public enum TrayState {
  /**
   * Logged in, The Agency HQ is responding, and syncing is working. Shown as a checkmark.
   */
  HEALTHY,

  /**
   * No usable credential — either nothing is stored or The Agency rejected the token. Shown as a question mark.
   */
  LOGGED_OUT,

  /**
   * Logged in but The Agency HQ cannot be reached. Shown as an exclamation point.
   */
  UNREACHABLE;

  /**
   * Classifies one briefing result into the state the icon should show. Every classification other than a failure is
   * an answer from The Agency, including a 403 — unreachable and rejected are the two cases where it never responded
   * with a briefing.
   *
   * @param result The result of a receive cycle's briefing call.
   * @return The state to show.
   */
  public static TrayState classify(BriefingResult result) {
    return switch (result) {
      case BriefingResult.Failed failed -> failed.authenticationFailure() ? LOGGED_OUT : UNREACHABLE;
      case BriefingResult.Forbidden _ -> HEALTHY;
      case BriefingResult.NotModified _ -> HEALTHY;
      case BriefingResult.Updated _ -> HEALTHY;
    };
  }
}

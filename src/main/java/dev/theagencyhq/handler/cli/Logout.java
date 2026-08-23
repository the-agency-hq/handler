/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The {@code logout} subcommand: discards the stored tokens. Logging out twice is not an error.
 *
 * @author Brian Pontarelli
 */
public class Logout {
  private final PrintStream out;
  private final TokenStore tokenStore;

  public Logout(TokenStore tokenStore, PrintStream out) {
    this.tokenStore = tokenStore;
    this.out = out;
  }

  public int run() {
    out.println(tokenStore.clear() ? "Logged out." : "Not logged in.");
    return 0;
  }
}

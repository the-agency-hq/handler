/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module dev.theagencyhq.handler;

/**
 * The {@code sync} subcommand: one receive pass then one distribute pass — exactly the daemon's startup pass, run to
 * completion and reported through the exit code.
 *
 * @author Brian Pontarelli
 */
public class Sync {
  private final Handler handler;

  public Sync(Handler handler) {
    this.handler = handler;
  }

  public int run(boolean force) {
    return handler.receiveAndDistribute(force).clean() ? 0 : 1;
  }
}

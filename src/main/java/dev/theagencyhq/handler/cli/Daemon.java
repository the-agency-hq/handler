/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The {@code daemon} subcommand: runs the credential preflight, then the daemon in the foreground until it is shut
 * down. The preflight verdict is not fatal — exiting on a missing credential just crash-loops under launchd and
 * systemd, invisibly, because their restart policies treat the non-zero exit as a failure to retry. It only picks the
 * state the tray shows until the first receive cycle answers. The receive loop re-reads the stored tokens on every
 * 401, so a {@code handler login} run later is adopted without a restart.
 *
 * @author Brian Pontarelli
 */
public class Daemon {
  private static final System.Logger LOG = System.getLogger(Daemon.class.getName());

  private final Credentials credentials;
  private final Handler handler;
  private final PrintStream out;

  public Daemon(Handler handler, Credentials credentials, PrintStream out) {
    this.handler = handler;
    this.credentials = credentials;
    this.out = out;
  }

  public int run() {
    TrayState initial = TrayState.HEALTHY;
    try {
      credentials.verify();
    } catch (IssuerUnreachableException e) {
      initial = TrayState.UNREACHABLE;
      out.println(e.getMessage());
      LOG.log(System.Logger.Level.WARNING, "Starting without a verified credential. Message was [{0}]", e.getMessage());
    } catch (AuthenticationException e) {
      initial = TrayState.LOGGED_OUT;
      out.println(e.getMessage());
      LOG.log(System.Logger.Level.WARNING, "Starting without a verified credential. Message was [{0}]", e.getMessage());
    }

    handler.daemon(initial);
    return 0;
  }
}

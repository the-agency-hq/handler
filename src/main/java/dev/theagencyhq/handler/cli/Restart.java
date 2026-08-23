/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The {@code restart} subcommand: stops then starts the installed daemon through the platform supervisor. The daemon
 * reads {@code handler.json} once at startup, so this is how a configuration change is adopted. Restarting a daemon
 * that is not running just starts it, matching {@code systemctl restart}.
 *
 * @author Brian Pontarelli
 */
public class Restart extends ProcessCommand {
  private static final String SUCCESS = "Restarted the Handler daemon.";

  public Restart(HandlerPaths paths, Path home, boolean macOS, Executor executor, PrintStream out) {
    super(paths, home, macOS, executor, out);
  }

  public int run() {
    if (!installed()) {
      return notInstalled();
    }

    if (!macOS) {
      return report(SUCCESS, require("systemctl", "--user", "restart", SERVICE));
    }

    ExecutionResult uid = uid();
    if (uid.failed()) {
      return report(SUCCESS, uid);
    }

    // A non-zero bootout exit only means the agent was not loaded — a restart also starts a stopped daemon
    ExecutionResult bootout = runProcess("launchctl", "bootout", "gui/" + uid.stdout() + "/" + DAEMON_LABEL);

    // The bootout above makes "already loaded" impossible, so a non-zero bootstrap exit is a real failure
    ExecutionResult bootstrap = require("launchctl", "bootstrap", "gui/" + uid.stdout(), daemonPlist().toString());

    return report(SUCCESS, bootout, bootstrap);
  }
}

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The {@code start} subcommand: starts the installed daemon through the platform supervisor — launchd on macOS,
 * systemd on Linux — so the daemon it starts is supervised exactly like one started at login. Starting a daemon that
 * is already running is not an error.
 *
 * @author Brian Pontarelli
 */
public class Start extends ProcessCommand {
  private static final String SUCCESS = "Started the Handler daemon.";

  public Start(HandlerPaths paths, Path home, boolean macOS, Executor executor, PrintStream out) {
    super(paths, home, macOS, executor, out);
  }

  public int run() {
    if (!installed()) {
      return notInstalled();
    }

    if (!macOS) {
      // [systemctl start] succeeds on an already-running service, so a non-zero exit here is a real failure
      return report(SUCCESS, require("systemctl", "--user", "start", SERVICE));
    }

    ExecutionResult uid = uid();
    if (uid.failed()) {
      return report(SUCCESS, uid);
    }

    // A non-zero bootstrap exit only means the agent is already loaded, which is the state start asks for
    return report(SUCCESS, runProcess("launchctl", "bootstrap", "gui/" + uid.stdout(), daemonPlist().toString()));
  }
}

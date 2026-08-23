/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The {@code stop} subcommand: stops the installed daemon through the platform supervisor. Killing the process
 * directly would only have launchd or systemd restart it, so asking the supervisor is the one real stop. The daemon
 * stays stopped until {@code handler start} or the next login. Stopping a daemon that is not running is not an error.
 *
 * @author Brian Pontarelli
 */
public class Stop extends ProcessCommand {
  private static final String SUCCESS = "Stopped the Handler daemon. Run [handler start] to start it again.";

  public Stop(HandlerPaths paths, Path home, boolean macOS, Executor executor, PrintStream out) {
    super(paths, home, macOS, executor, out);
  }

  public int run() {
    if (!installed()) {
      return notInstalled();
    }

    if (!macOS) {
      // [systemctl stop] succeeds on an already-stopped service, so a non-zero exit here is a real failure
      return report(SUCCESS, require("systemctl", "--user", "stop", SERVICE));
    }

    ExecutionResult uid = uid();
    if (uid.failed()) {
      return report(SUCCESS, uid);
    }

    // A non-zero bootout exit only means the agent was not loaded, which is the state stop asks for
    return report(SUCCESS, runProcess("launchctl", "bootout", "gui/" + uid.stdout() + "/" + DAEMON_LABEL));
  }
}

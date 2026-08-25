/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;

/**
 * The {@code help} subcommand: prints the usage text, which is also what an unknown command gets.
 *
 * @author Brian Pontarelli
 */
public class Help {
  private final PrintStream out;

  public Help(PrintStream out) {
    this.out = out;
  }

  public int run() {
    out.println("""
        Usage: handler [command]

          daemon             Run the receive and distribute loops in the foreground (default)
          start              Start the installed daemon through launchd or systemd
          stop               Stop the daemon until [handler start] or the next login
          restart            Restart the daemon, adopting configuration changes
          sync [--force]     Run one receive pass then one distribute pass, then exit
          status             Print resolved paths, stored Organizations, and every Location's state
          init               Choose an Organization and write agent-location.json in the current directory
          init-source        Scaffold a Brief Source repository in the current directory
          login              Log in to The Agency through your browser
          logout             Discard the stored tokens
          uninstall [--yes]  Stop the daemon and remove the Handler, leaving other Agency HQ configuration in place
          help               Print this message
          --version          Print the version
        """);
    return 0;
  }
}

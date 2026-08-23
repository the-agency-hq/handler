/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;

/**
 * Argument dispatch and nothing else: parses the command word and hands off to the subcommand classes, which contain
 * all of the behavior.
 *
 * @author Brian Pontarelli
 */
public class HandlerCLI {
  private final Daemon daemon;
  private final Help help;
  private final Init init;
  private final Login login;
  private final Logout logout;
  private final PrintStream out;
  private final Restart restart;
  private final Start start;
  private final Status status;
  private final Stop stop;
  private final Sync sync;
  private final Uninstall uninstall;
  private final Version version;

  public HandlerCLI(Daemon daemon, Start start, Stop stop, Restart restart, Sync sync, Status status, Init init,
                    Login login, Logout logout, Uninstall uninstall, Help help, Version version, PrintStream out) {
    this.daemon = daemon;
    this.start = start;
    this.stop = stop;
    this.restart = restart;
    this.sync = sync;
    this.status = status;
    this.init = init;
    this.login = login;
    this.logout = logout;
    this.uninstall = uninstall;
    this.help = help;
    this.version = version;
    this.out = out;
  }

  public int run(String... args) {
    String command = args.length == 0 ? "daemon" : args[0];
    return switch (command) {
      case "daemon" -> daemon.run();
      case "help", "--help", "-h" -> help.run();
      case "init" -> init.run();
      case "login" -> login.run();
      case "logout" -> logout.run();
      case "restart" -> restart.run();
      case "start" -> start.run();
      case "status" -> status.run();
      case "stop" -> stop.run();
      case "sync" -> sync.run(Arrays.asList(args).contains("--force"));
      case "uninstall" -> uninstall.run(Arrays.asList(args).contains("--yes"));
      case "version", "--version" -> version.run();
      default -> {
        out.println("Unknown command [" + command + "]");
        help.run();
        yield 1;
      }
    };
  }
}

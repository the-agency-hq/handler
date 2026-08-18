/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import dev.theagencyhq.handler.*;
import dev.theagencyhq.handler.apply.*;
import dev.theagencyhq.handler.auth.*;
import dev.theagencyhq.handler.brief.*;
import dev.theagencyhq.handler.config.*;
import dev.theagencyhq.handler.location.*;

// Disambiguates java.util.jar.Attributes from java.lang.classfile.Attributes, both pulled in by the module import

/**
 * Argument dispatch and the subcommands. {@code status} recomputes everything from disk rather than reading
 * persisted state, so there is nothing to go stale.
 *
 * @author Brian Pontarelli
 */
public class HandlerCLI {
  private final LocationApplier applier;
  private final HandlerConfig config;
  private final Credentials credentials;
  private final Handler handler;
  private final Init init;
  private final Login login;
  private final PrintStream out;
  private final HandlerPaths paths;
  private final BriefPlanner planner;
  private final LocationScanner scanner;
  private final BriefStore store;
  private final TokenStore tokenStore;

  public HandlerCLI(HandlerPaths paths, HandlerConfig config, BriefStore store, LocationScanner scanner,
                    BriefPlanner planner, LocationApplier applier, Handler handler, Init init, Login login,
                    TokenStore tokenStore, Credentials credentials, PrintStream out) {
    this.paths = paths;
    this.config = config;
    this.store = store;
    this.scanner = scanner;
    this.planner = planner;
    this.applier = applier;
    this.handler = handler;
    this.init = init;
    this.login = login;
    this.tokenStore = tokenStore;
    this.credentials = credentials;
    this.out = out;
  }

  /**
   * Reads the version the build stamped into the jar's manifest. {@code Package.getImplementationVersion()} does not
   * work here — the JDK does not carry manifest attributes onto packages defined in a named module — so the manifest is
   * read straight out of this module.
   *
   * @return The jar's {@code Implementation-Version}, or {@code "dev"} when running from exploded classes, where there
   *     is no manifest to read.
   */
  private static String version() {
    try (InputStream is = HandlerCLI.class.getModule().getResourceAsStream("META-INF/MANIFEST.MF")) {
      if (is == null) {
        return "dev";
      }

      String version = new Manifest(is).getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
      return version == null ? "dev" : version;
    } catch (IOException e) {
      return "dev";
    }
  }

  public int run(String... args) {
    String command = args.length == 0 ? "daemon" : args[0];
    return switch (command) {
      case "daemon" -> daemon();
      case "sync" -> sync(Arrays.asList(args).contains("--force"));
      case "status" -> status();
      case "init" -> init.run();
      case "login" -> login();
      case "logout" -> logout();
      case "help", "--help", "-h" -> {
        usage();
        yield 0;
      }
      case "--version", "version" -> {
        out.println("handler " + version());
        yield 0;
      }
      default -> {
        out.println("Unknown command [" + command + "]");
        usage();
        yield 1;
      }
    };
  }

  /**
   * @return Whether an access token is stored, or that the file could not be read. A hand-mangled {@code tokens.json}
   *     is one of the things {@code status} is run to find, so it is reported rather than thrown — the daemon already
   *     treats that file as non-fatal, and the diagnostic command has no business being stricter.
   */
  private String accessTokenState() {
    try {
      return tokenStore.load().present() ? "present" : "absent";
    } catch (AuthenticationException e) {
      return "unreadable (run [handler login] to replace it)";
    }
  }

  /**
   * Runs the credential preflight, then the daemon. The preflight is deliberately fatal: a Handler that starts without
   * a working credential looks healthy to launchd while receiving nothing, and the developer finds out hours later
   * from an empty Location rather than now, from a message.
   *
   * @return The exit code.
   */
  private int daemon() {
    try {
      credentials.verify();
    } catch (AuthenticationException e) {
      out.println(e.getMessage());
      return 1;
    }

    handler.daemon();
    return 0;
  }

  private String describe(Location location) {
    if (store.latest(location.organizationId()).isEmpty() && !store.revoked(location.organizationId())) {
      return "no brief";
    }

    LocationPlan plan = planFor(location);
    if (plan == null) {
      return "invalid brief";
    }

    return switch (applier.inspect(location, plan)) {
      case CHANGED -> "changed";
      case CONFLICT -> "conflict";
      case UNCHANGED -> "unchanged";
      case UNREADABLE -> "unreadable";
    };
  }

  private int login() {
    try {
      String email = login.run(out);
      out.println(email == null ? "Login successful." : "Logged in as [" + email + "]");
      return 0;
    } catch (AuthenticationException e) {
      out.println(e.getMessage());
      return 1;
    }
  }

  private int logout() {
    out.println(tokenStore.clear() ? "Logged out." : "Not logged in.");
    return 0;
  }

  /**
   * Asks the IdP whether the stored access token is still good. This is the one check that catches a revoked token —
   * a revoked token decodes cleanly and only the IdP knows it is dead.
   *
   * @return A one-line verdict. Never throws: {@code status} is the command a developer runs when something is
   *     already wrong, so every failure has to come back as text rather than a stack trace.
   */
  private String introspectState() {
    try {
      Introspection introspection = credentials.introspect();
      String who = introspection.email() == null ? introspection.subject() : introspection.email();
      return "valid — " + who + ", expires " + introspection.expiresAtInstant();
    } catch (IssuerUnreachableException e) {
      return "unknown — " + e.getMessage();
    } catch (AuthenticationException e) {
      return "invalid — " + e.getMessage();
    }
  }

  private LocationPlan planFor(Location location) {
    Optional<StoredBrief> stored = store.latest(location.organizationId());
    if (stored.isEmpty() || store.revoked(location.organizationId())) {
      return LocationPlan.EMPTY;
    }

    try {
      return planner.plan(stored.get(), location);
    } catch (BriefPlanner.InvalidPlanException e) {
      return null;
    }
  }

  private int status() {
    out.println("configFile   " + paths.configFile());
    out.println("tokensFile   " + paths.tokensFile());
    out.println("storeRoot    " + paths.storeRoot());
    out.println("logFile      " + paths.logFile());
    out.println("theAgencyURL " + config.theAgencyURL());
    out.println("authURL      " + config.authURL());
    out.println("accessToken  " + accessTokenState());
    out.println("introspect   " + introspectState());
    out.println();

    out.println("Organizations");
    Set<String> organizationIds = store.organizationIds();
    if (organizationIds.isEmpty()) {
      out.println("  (none)");
    } else {
      for (String organizationId : organizationIds) {
        String version = store.latest(organizationId)
                              .map(stored -> Integer.toString(stored.version()))
                              .orElse("none");
        out.println("  " + organizationId + "  version=" + version
            + (store.revoked(organizationId) ? "  revoked" : ""));
      }
    }

    out.println();
    out.println("Locations");
    List<Location> locations = scanner.scan();
    if (locations.isEmpty()) {
      out.println("  (none)");
      return 0;
    }

    for (Location location : locations) {
      out.println("  " + location.root() + "  " + describe(location));
    }

    return 0;
  }

  private int sync(boolean force) {
    DistributeThread.Summary summary = handler.receiveAndDistribute(force);
    return summary.clean() ? 0 : 1;
  }

  private void usage() {
    out.println("""
        Usage: handler [command]
        
          daemon             Run the receive and distribute loops in the foreground (default)
          sync [--force]     Run one receive pass then one distribute pass, then exit
          status             Print resolved paths, stored Organizations, and every Location's state
          init               Choose an Organization and write agent-location.json in the current directory
          login              Log in to The Agency through your browser
          logout             Discard the stored tokens
          help               Print this message
          --version          Print the version
        """);
  }
}

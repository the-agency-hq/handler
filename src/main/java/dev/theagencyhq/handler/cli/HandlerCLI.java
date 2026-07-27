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
import dev.theagencyhq.handler.brief.*;
import dev.theagencyhq.handler.config.*;
import dev.theagencyhq.handler.location.*;

// Disambiguates java.util.jar.Attributes from java.lang.classfile.Attributes, both pulled in by the module import

/**
 * Argument dispatch and the three subcommands. {@code status} recomputes everything from disk rather than reading
 * persisted state, so there is nothing to go stale.
 *
 * @author Brian Pontarelli
 */
public class HandlerCLI {
  private final LocationApplier applier;
  private final HandlerConfig config;
  private final Handler handler;
  private final PrintStream out;
  private final HandlerPaths paths;
  private final BriefPlanner planner;
  private final LocationScanner scanner;
  private final BriefStore store;

  public HandlerCLI(HandlerPaths paths, HandlerConfig config, BriefStore store, LocationScanner scanner,
                    BriefPlanner planner, LocationApplier applier, Handler handler, PrintStream out) {
    this.paths = paths;
    this.config = config;
    this.store = store;
    this.scanner = scanner;
    this.planner = planner;
    this.applier = applier;
    this.handler = handler;
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
      case "daemon" -> {
        handler.daemon();
        yield 0;
      }
      case "sync" -> sync(Arrays.asList(args).contains("--force"));
      case "status" -> status();
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
    out.println("storeRoot    " + paths.storeRoot());
    out.println("logFile      " + paths.logFile());
    out.println("theAgencyURL " + config.theAgencyURL());
    out.println("accessToken  " + (config.accessToken().isEmpty() ? "absent" : "present"));
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
          help               Print this message
          --version          Print the version
        """);
  }
}

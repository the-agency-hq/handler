/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The {@code status} subcommand: prints the resolved paths, the credential state, every stored Organization, and
 * every Location's state. Everything is recomputed from disk rather than read from persisted state, so there is
 * nothing to go stale.
 *
 * @author Brian Pontarelli
 */
public class Status {
  private final LocationApplier applier;
  private final HandlerConfig config;
  private final Credentials credentials;
  private final PrintStream out;
  private final HandlerPaths paths;
  private final BriefPlanner planner;
  private final LocationScanner scanner;
  private final BriefStore store;
  private final TokenStore tokenStore;

  public Status(HandlerPaths paths, HandlerConfig config, BriefStore store, LocationScanner scanner,
                BriefPlanner planner, LocationApplier applier, TokenStore tokenStore, Credentials credentials,
                PrintStream out) {
    this.paths = paths;
    this.config = config;
    this.store = store;
    this.scanner = scanner;
    this.planner = planner;
    this.applier = applier;
    this.tokenStore = tokenStore;
    this.credentials = credentials;
    this.out = out;
  }

  public int run() {
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
}

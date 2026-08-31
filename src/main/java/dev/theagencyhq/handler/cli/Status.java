/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The {@code status} subcommand: prints the resolved paths, the credential state, every stored Organization, and what
 * the next distribute cycle will do at every Location the daemon found on its last run. The Locations come from the
 * state file the daemon writes, so this command never scans the start directory. Each Location is inspected with a
 * pure read; nothing on disk is changed.
 *
 * @author Brian Pontarelli
 */
public class Status {
  private static final DateTimeFormatter LAST_RUN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                                                     .withZone(ZoneId.systemDefault());

  private final LocationApplier applier;
  private final HandlerConfig config;
  private final Credentials credentials;
  private final PrintStream out;
  private final HandlerPaths paths;
  private final BriefPlanner planner;
  private final StateStore stateStore;
  private final BriefStore store;
  private final TokenStore tokenStore;

  public Status(HandlerPaths paths, HandlerConfig config, BriefStore store, StateStore stateStore,
                BriefPlanner planner, LocationApplier applier, TokenStore tokenStore, Credentials credentials,
                PrintStream out) {
    this.paths = paths;
    this.config = config;
    this.store = store;
    this.stateStore = stateStore;
    this.planner = planner;
    this.applier = applier;
    this.tokenStore = tokenStore;
    this.credentials = credentials;
    this.out = out;
  }

  public int run() {
    line("Config file:", paths.configFile());
    line("Tokens file:", paths.tokensFile());
    line("Store root:", paths.storeRoot());
    line("Log file:", paths.logFile());
    line("State file:", paths.stateFile());
    line("The Agency URL:", config.theAgencyURL());
    line("Auth URL:", config.authURL());
    line("Access token:", accessTokenState());
    line("Introspect:", introspectState());
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
    locations();
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

  /**
   * Works out what the next distribute cycle will do at one Location. This mirrors the plan selection in
   * {@code DistributeThread.applyTo}, then asks the applier for a read-only inspection instead of applying.
   *
   * @param location The Location as the daemon last recorded it.
   * @return A short description for the developer.
   */
  private String describe(Location location) {
    if (!Files.isRegularFile(location.root().resolve(LocationScanner.MARKER_FILENAME), LinkOption.NOFOLLOW_LINKS)) {
      return "Removed";
    }

    String organizationId = location.organizationId();
    Optional<StoredBrief> stored = store.latest(organizationId);
    boolean revoked = store.revoked(organizationId);
    if (stored.isEmpty() && !revoked) {
      return "No Brief";
    }

    LocationPlan plan;
    if (stored.isPresent() && !revoked) {
      try {
        plan = planner.plan(stored.get(), location);
      } catch (BriefPlanner.InvalidPlanException e) {
        return "Invalid Brief: " + e.getMessage();
      }
    } else {
      plan = LocationPlan.EMPTY;
    }

    return switch (applier.inspect(location, plan)) {
      case CHANGED -> revoked ? "Pending removal" : "Pending new version";
      case CONFLICT -> "Skipped due to conflicts";
      case UNCHANGED -> "Up-to-date";
      case UNREADABLE -> "Unreadable";
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

  private void line(String label, Object value) {
    out.println(String.format("%-16s %s", label, value));
  }

  private void locations() {
    Optional<HandlerState> loaded;
    try {
      loaded = stateStore.load();
    } catch (StateStore.UnreadableStateException e) {
      out.println("Locations");
      out.println("  Unknown. The state file could not be read (" + e.getMessage() + "). This status output will"
          + " update the next time the daemon runs.");
      return;
    }

    if (loaded.isEmpty()) {
      out.println("Locations");
      out.println("  Unknown. This status output will update the next time the daemon runs.");
      return;
    }

    HandlerState state = loaded.get();
    out.println(state.lastRun() == null ? "Locations"
                                        : "Locations (last daemon run " + LAST_RUN.format(state.lastRun()) + ")");
    if (state.locations().isEmpty()) {
      out.println("  (none)");
      return;
    }

    for (LocationEntry entry : state.locations()) {
      out.println("  " + entry.root());
      out.println("    Mission types: " + (entry.missionTypes().isEmpty() ? "all"
                                                                           : String.join(", ", entry.missionTypes())));
      out.println("    Agent types:   " + (entry.agentTypes().isEmpty() ? "all"
                                                                         : String.join(", ", entry.agentTypes())));
      out.println("    Status:        " + describe(entry.toLocation()));
    }
  }
}

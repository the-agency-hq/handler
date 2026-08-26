/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The distribute service: find every Location and make it match the Brief its Organization published. Every Location
 * is independent — an exception applying one is logged and never aborts the others.
 *
 * <p>Runs every {@code distributeIntervalSeconds}, and wakes early whenever {@link ReceiveThread} changed the store.
 * Every cycle ends by writing the state file, which is how {@code handler status} learns which Locations exist
 * without scanning for them.
 *
 * @author Brian Pontarelli
 */
public class DistributeThread extends IntervalThread {
  private static final System.Logger LOG = System.getLogger(DistributeThread.class.getName());

  private final LocationApplier applier;
  private final HandlerConfig config;
  private final ObjIntConsumer<Summary> observer;
  private final BriefPlanner planner;
  private final LocationScanner scanner;
  private final StateStore stateStore;
  private final BriefStore store;

  public DistributeThread(HandlerConfig config, BriefStore store, LocationScanner scanner, BriefPlanner planner,
                          LocationApplier applier, StateStore stateStore) {
    this(config, store, scanner, planner, applier, stateStore, (_, _) -> {
    });
  }

  public DistributeThread(HandlerConfig config, BriefStore store, LocationScanner scanner, BriefPlanner planner,
                          LocationApplier applier, StateStore stateStore, ObjIntConsumer<Summary> observer) {
    super("handler-distribute");
    this.config = config;
    this.store = store;
    this.scanner = scanner;
    this.planner = planner;
    this.applier = applier;
    this.stateStore = stateStore;
    this.observer = observer;
  }

  public Summary distribute(boolean force) {
    List<Location> locations = scanner.scan();
    Map<ApplyResult, Integer> counts = new EnumMap<>(ApplyResult.class);
    Set<String> deferredPurges = new HashSet<>();
    List<LocationEntry> entries = new ArrayList<>(locations.size());

    // One Location after another. Measured against a virtual-thread fan-out bounded to 8: the steady-state cycle,
    // where every Location is UNCHANGED, is 40ms serially across 100 Locations, and a version bump that rewrites
    // every file is 7.2s against 4.7s. Only 1.5x, because roughly 95% of a changed cycle is Manifest.append()'s
    // fsync and those serialize on the filesystem journal no matter how many threads issue them. That is not worth
    // a Semaphore, an ExecutorService, and Future collection whose interrupt path has to mark every uncollected
    // Location FAILED to keep a revoked Organization from being purged on the assumption that silence means success.
    for (Location location : locations) {
      LocationResult result;
      try {
        result = applyTo(location, force);
      } catch (RuntimeException e) {
        // Every Location is independent. This must be recorded rather than skipped: an unrecorded Location is
        // invisible to the deferred-purge set below, so a revoked Organization could be purged while this
        // Location's true state is still unknown.
        LOG.log(System.Logger.Level.ERROR, "Location [" + location.root() + "] failed unexpectedly", e);
        result = new LocationResult(ApplyResult.FAILED, "Failed unexpectedly: " + e.getMessage());
      }

      if (result == null) {
        // Skipped without teardown - it belongs in no bucket, but it is still a Location the developer can see
        entries.add(LocationEntry.of(location, LocationStatus.SUCCESS, null));
        continue;
      }

      ApplyResult applyResult = result.applyResult();
      counts.merge(applyResult, 1, Integer::sum);
      if (applyResult == ApplyResult.APPLIED || applyResult == ApplyResult.UNCHANGED) {
        entries.add(LocationEntry.of(location, LocationStatus.SUCCESS, null));
      } else {
        entries.add(LocationEntry.of(location, LocationStatus.ERROR, result.message()));
        deferredPurges.add(location.organizationId());
      }
    }

    // Before the purge, so the cycle's findings are recorded even if the purge fails
    writeState(entries);

    if (scanner.startDirectoryUnreadable()) {
      // An empty or incomplete scan is indistinguishable from "no Locations exist" for any Organization the scan
      // failed to reach. Purging here would tear down a revoked Organization whose Location is merely unreachable
      // right now, and its files would live on that machine forever once the drive comes back.
      LOG.log(System.Logger.Level.WARNING, "Deferring every revocation purge because the start directory could not be fully scanned");
    } else {
      purgeCompletedRevocations(deferredPurges);
    }

    Summary summary = new Summary(counts.getOrDefault(ApplyResult.APPLIED, 0),
                                  counts.getOrDefault(ApplyResult.UNCHANGED, 0),
                                  counts.getOrDefault(ApplyResult.SKIPPED_CONFLICT, 0),
                                  counts.getOrDefault(ApplyResult.FAILED, 0));
    LOG.log(System.Logger.Level.INFO,
        "applied={0} unchanged={1} conflict={2} failed={3}",
        summary.applied(), summary.unchanged(), summary.conflict(), summary.failed());

    observer.accept(summary, locations.size());
    return summary;
  }

  @Override
  protected void execute() {
    distribute(false);
  }

  @Override
  protected long intervalSeconds() {
    return config.distributeIntervalSeconds();
  }

  /**
   * @return The result, or null when the Location was skipped because its Organization has no Brief.
   */
  private LocationResult applyTo(Location location, boolean force) {
    String organizationId = location.organizationId();
    Optional<StoredBrief> stored = store.latest(organizationId);
    boolean revoked = store.revoked(organizationId);

    if (stored.isEmpty() && !revoked) {
      // Distinct from revocation: there is nothing to tear down and the developer may simply not have access yet
      LOG.log(System.Logger.Level.WARNING, "Location [{0}] names Organization [{1}] which has no Brief; skipping it",
              location.root(), organizationId);
      return null;
    }

    LocationPlan plan;
    if (stored.isPresent() && !revoked) {
      try {
        plan = planner.plan(stored.get(), location);
      } catch (BriefPlanner.InvalidPlanException e) {
        LOG.log(System.Logger.Level.ERROR, "Location [" + location.root() + "] was skipped: " + e.getMessage(), e);
        return new LocationResult(ApplyResult.FAILED, "Invalid Brief: " + e.getMessage());
      }
    } else {
      plan = LocationPlan.EMPTY;
    }

    ApplyResult result = applier.apply(location, plan, force);
    return new LocationResult(result, switch (result) {
      case APPLIED, UNCHANGED -> null;
      case FAILED -> "Failed to apply. See the log for details.";
      case SKIPPED_CONFLICT -> "Skipped due to conflicts. Run [handler sync --force] to adopt the conflicting files.";
    });
  }

  private void purgeCompletedRevocations(Set<String> deferred) {
    for (String organizationId : store.organizationIds()) {
      if (!store.revoked(organizationId)) {
        continue;
      }

      if (deferred.contains(organizationId)) {
        LOG.log(System.Logger.Level.WARNING,
                "Deferring the purge of revoked Organization [{0}] until every Location tears down cleanly",
                organizationId);
        continue;
      }

      store.purge(organizationId);
    }
  }

  private void writeState(List<LocationEntry> entries) {
    try {
      stateStore.store(new HandlerState(Instant.now(), entries));
    } catch (RuntimeException e) {
      // A status file that cannot be written must never stop syncing
      LOG.log(System.Logger.Level.WARNING, "Unable to write the state file", e);
    }
  }

  public record Summary(int applied, int unchanged, int conflict, int failed) {
    public boolean clean() {
      return conflict == 0 && failed == 0;
    }
  }

  /**
   * One Location's {@link ApplyResult} plus the reason it is not a success, which is what the state file records.
   */
  private record LocationResult(ApplyResult applyResult, String message) {
  }
}

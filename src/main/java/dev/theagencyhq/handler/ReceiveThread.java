/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The receive service: ask The Agency what changed, verify it, and store it. Revocation is only <em>marked</em> here —
 * {@link DistributeThread} tears the Locations down and purges, so the Handler never deletes the manifest and Brief it
 * needs in order to clean up.
 *
 * <p>Runs every {@code receiveIntervalSeconds}. The nudge is sent once per cycle, after every Brief has been written,
 * so the distribute that follows sees the whole batch rather than starting against a half-written store.
 *
 * @author Brian Pontarelli
 */
public class ReceiveThread extends IntervalThread {
  private static final System.Logger LOG = System.getLogger(ReceiveThread.class.getName());

  private final AgencyClient agency;
  private final HandlerConfig config;
  private final DistributeThread distributeThread;
  private final Consumer<BriefingResult> observer;
  private final BriefStore store;

  public ReceiveThread(HandlerConfig config, AgencyClient agency, BriefStore store,
                       DistributeThread distributeThread) {
    this(config, agency, store, distributeThread, _ -> {
    });
  }

  public ReceiveThread(HandlerConfig config, AgencyClient agency, BriefStore store, DistributeThread distributeThread,
                       Consumer<BriefingResult> observer) {
    super("handler-receive");
    this.config = config;
    this.agency = agency;
    this.store = store;
    this.distributeThread = distributeThread;
    this.observer = observer;
  }

  /**
   * One receive cycle followed by the nudge, which is exactly what the interval loop runs. Public because
   * {@link Handler#receive()} and the tests need the real path rather than a 10-second approximation of it —
   * {@code HandlerConfig} clamps the interval at 10 seconds, so waiting one out is the only alternative.
   */
  @Override
  public void execute() {
    if (receive()) {
      distributeThread.nudge();
    }
  }

  /**
   * Runs one receive cycle without nudging. This is the {@code handler sync} path, where the distribute is the next
   * statement and there is nothing to signal.
   *
   * @return True if this cycle changed the store, which is what gates the nudge.
   */
  public boolean receive() {
    // The contract is that this never throws. AgencyClient already guarantees it for the network side, but every
    // BriefStore call can raise UncheckedIOException on a local disk problem, and an escape here would end the
    // interval loop. IntervalThread guards the loop too; this is the inner layer.
    try {
      return receiveOrThrow();
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "The receive cycle failed against the local store", e);
      return false;
    }
  }

  @Override
  protected long intervalSeconds() {
    return config.receiveIntervalSeconds();
  }

  private boolean apply(BriefingResult.Updated updated) {
    boolean changed = false;
    for (Brief brief : updated.briefs()) {
      if (verify(brief)) {
        store.store(brief);
        changed = true;
      }
    }

    Set<String> revoked = new TreeSet<>(store.organizationIds());
    revoked.removeAll(updated.organizationIds());
    for (String organizationId : revoked) {
      if (!store.revoked(organizationId)) {
        store.markRevoked(organizationId);
        changed = true;
      }
    }

    return changed;
  }

  private boolean receiveOrThrow() {
    List<CurrentVersion> versions = store.allCurrent()
                                         .stream()
                                         .map(stored -> new CurrentVersion(stored.organizationId(), stored.version(),
                                                                           stored.brief().checksum()))
                                         .toList();

    BriefingResult result = agency.briefing(versions);
    observer.accept(result);

    return switch (result) {
      case BriefingResult.NotModified _ -> {
        LOG.log(System.Logger.Level.DEBUG, "The Agency reports every version current");
        yield false;
      }
      case BriefingResult.Failed failed -> {
        // A 401 needs a human; everything else is degraded-but-recovering. Either way the store stays authoritative.
        System.Logger.Level level = failed.authenticationFailure() ? System.Logger.Level.ERROR
                                                                   : System.Logger.Level.WARNING;
        LOG.log(level, "Receive failed: [{0}]", failed.reason());
        yield false;
      }
      case BriefingResult.Forbidden _ -> {
        // Only NEWLY revoked organizations count. A purge can be deferred indefinitely (§8.8), so a prolonged 403
        // would otherwise re-nudge on every single cycle even though nothing changed since the last one.
        Set<String> newlyRevoked = new TreeSet<>();
        for (String organizationId : store.organizationIds()) {
          if (!store.revoked(organizationId)) {
            store.markRevoked(organizationId);
            newlyRevoked.add(organizationId);
          }
        }

        if (!newlyRevoked.isEmpty()) {
          LOG.log(System.Logger.Level.ERROR, "The Agency reports no entitlements; revoked [{0}]", newlyRevoked);
        }

        yield !newlyRevoked.isEmpty();
      }
      case BriefingResult.Updated updated -> apply(updated);
    };
  }

  private boolean verify(Brief brief) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }

    for (BriefFile file : brief.files()) {
      byte[] decoded;
      try {
        decoded = file.decoded();
      } catch (IllegalArgumentException e) {
        LOG.log(System.Logger.Level.ERROR, "Organization [" + brief.organization().id() + "] version ["
                                            + brief.version() + "] file [" + file.path() + "] is undecodable", e);
        return false;
      }

      String actual = HexFormat.of().formatHex(digest.digest(decoded));
      if (!actual.equals(file.checksum())) {
        LOG.log(System.Logger.Level.ERROR,
                "Checksum mismatch for Organization [{0}] version [{1}] file [{2}]: expected [{3}] but computed [{4}]",
                brief.organization().id(), brief.version(), file.path(), file.checksum(), actual);
        return false;
      }
    }

    return true;
  }
}

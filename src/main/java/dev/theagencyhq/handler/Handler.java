/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * Owns the two service threads, the startup pass, and process lifecycle. Each thread runs its own interval and wakes
 * itself, so a slow receive can never delay a distribute. The store on disk is the only shared state between them; the
 * nudge carries no payload and is never a correctness requirement.
 *
 * @author Brian Pontarelli
 */
public class Handler {
  private static final System.Logger LOG = System.getLogger(Handler.class.getName());

  private final HandlerConfig config;
  private final DistributeThread distributeThread;
  private final TrayFeed feed;
  private final ReceiveThread receiveThread;
  private final CountDownLatch shutdown = new CountDownLatch(1);
  private final TokenWatcher watcher;

  public Handler(HandlerConfig config, AgencyClient agency, BriefStore store, DistributeThread distributeThread) {
    this(config, agency, store, distributeThread, null, null);
  }

  public Handler(HandlerConfig config, AgencyClient agency, BriefStore store, DistributeThread distributeThread,
                 TrayFeed feed) {
    this(config, agency, store, distributeThread, feed, null);
  }

  /**
   * @param feed    The tray feed, or null for a CLI run that serves no tray.
   * @param watcher The token file watcher, or null to rely on the receive interval alone to notice a login.
   */
  public Handler(HandlerConfig config, AgencyClient agency, BriefStore store, DistributeThread distributeThread,
                 TrayFeed feed, TokenWatcher watcher) {
    this.config = config;
    this.distributeThread = distributeThread;
    this.feed = feed;
    this.watcher = watcher;
    this.receiveThread = new ReceiveThread(config, agency, store, distributeThread,
        feed == null ? _ -> {
        } : feed::received);
  }

  /**
   * Runs the startup pass, starts both threads, and blocks until {@link #shutdown()}.
   *
   * @param initial The state the tray shows until the startup pass's first briefing result replaces it — the CLI's
   *     credential preflight verdict.
   */
  public void daemon(TrayState initial) {
    if (feed != null) {
      feed.start(initial);
    }

    // Before the startup pass, so a login that lands while it runs is not missed. A nudge before the receive thread
    // has started is recorded, not lost, and the first interval wait honours it.
    if (watcher != null) {
      watcher.start(receiveThread::nudge);
    }

    // Run the initial pass
    receiveAndDistribute(false);

    // Each thread waits before its first run, so its interval is also its initial delay and neither immediately
    // repeats the startup pass. Distribute starts first so a nudge from the very first receive is never dropped.
    distributeThread.start();
    receiveThread.start();

    LOG.log(System.Logger.Level.INFO, "Handler started with receive every [{0}]s and distribute every [{1}]s",
        config.receiveIntervalSeconds(), config.distributeIntervalSeconds());

    try {
      shutdown.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * FOR TESTING: Runs one receive cycle with the nudge armed. This is exactly what the receive thread's interval loop
   * invokes.
   */
  public void receive() {
    receiveThread.execute();
  }

  public void shutdown() {
    if (feed != null) {
      feed.stop();
    }

    // The watcher first, so it stops feeding nudges before the receive side is told to stop
    if (watcher != null) {
      watcher.stop();
    }

    // Receive first, so it stops feeding nudges before the distribute side is told to stop
    receiveThread.shutdown();
    distributeThread.shutdown();

    try {
      // Give an in-flight apply time to finish; a hard kill mid-apply is still safe by the manifest invariant.
      // join() on a thread that was never started returns immediately, which is the handler-sync path.
      receiveThread.join(10_000);
      distributeThread.join(10_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    shutdown.countDown();
  }

  /**
   * Performs exactly the startup pass — one receive then one distribute — and returns the distribute summary. This
   * is also the implementation of {@code handler sync}, so the daemon's cold start and the one-shot command cannot
   * drift apart. No nudge is sent: the distribute is the next statement.
   *
   * @param force True to adopt conflicting files at every Location.
   * @return The distribute summary.
   */
  public DistributeThread.Summary receiveAndDistribute(boolean force) {
    // Receive first: on a machine with an empty store there is nothing to distribute until the Agency has been called
    try {
      receiveThread.receive();
    } catch (Throwable t) {
      LOG.log(System.Logger.Level.ERROR, "The receive step of the startup pass failed", t);
    }

    try {
      return distributeThread.distribute(force);
    } catch (Throwable t) {
      // Unguarded, a purge failure here would escape daemonAsync() before either thread is started, so the daemon
      // would never run at all rather than degrading one cycle
      LOG.log(System.Logger.Level.ERROR, "The distribute step of the startup pass failed", t);
      return new DistributeThread.Summary(0, 0, 0, 1);
    }
  }
}

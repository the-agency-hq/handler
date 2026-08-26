/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class HandlerTest extends BaseTest {
  private FakeAgency agency;
  private CountingDistributeThread distributeThread;
  private Handler handler;

  @Test
  public void aLoginWrittenWhileTheDaemonRunsWakesTheReceiveLoop() throws Exception {
    agency.script(304, "");
    agency.script(304, "");
    Path tokensFile = base.resolve("config/tokens.json");
    handler = handler(new TokenWatcher(tokensFile, () -> true));

    Thread caller = new Thread(() -> handler.daemon(TrayState.HEALTHY), "daemon-caller");
    caller.start();
    assertTrue(distributeThread.await(1, 5), "The startup pass must run");
    assertEquals(agency.paths().size(), 1, "The startup pass makes exactly one briefing request");

    new TokenStore(tokensFile).store(new Tokens("written-by-login", "refresh"));

    // Both intervals are an hour, so a second briefing request can only come from the watcher's nudge. The macOS
    // WatchService polls every two seconds, so allow well beyond that.
    long deadline = System.currentTimeMillis() + 10_000;
    while (agency.paths().size() < 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }

    assertEquals(agency.paths().size(), 2, "The login must trigger a receive cycle without waiting out the interval");

    handler.shutdown();
    caller.join(10_000);
    Assert.assertFalse(caller.isAlive(), "shutdown() must release the daemon with the watcher running");
  }

  @Test
  public void aReceiveThatChangesNothingSendsNoNudge() throws Exception {
    agency.script(304, "");
    handler = handler();
    distributeThread.start();

    handler.receive();
    Thread.sleep(500);

    assertEquals(distributeThread.count(), 0, "A 304 must not nudge");
  }

  @Test
  public void aStoredBriefNudgesTheDistributeThreadWithoutWaitingOutTheInterval() throws Exception {
    // Both intervals are an hour, so a `distribute` can only come from the nudge
    agency.script(200, response("42", 1));
    handler = handler();
    distributeThread.start();

    handler.receive();

    assertTrue(distributeThread.await(1, 5), "The nudge must trigger a distribute immediately");
    assertEquals(store.latest("42").orElseThrow().version(), 1);
  }

  @Test
  public void aThrowingDistributeDoesNotKillLaterRuns() throws Exception {
    agency.script(200, response("42", 1));
    agency.script(200, response("43", 1));
    handler = handler();
    distributeThread.start();

    distributeThread.throwOnce();
    handler.receive();
    assertTrue(distributeThread.await(1, 5), "The throwing run still counts as a run");

    handler.receive();
    assertTrue(distributeThread.await(2, 5), "A throwing distribute must not prevent later runs");
  }

  @Test
  public void concurrentNudgesCollapseIntoAtMostOneExtraDistribute() throws Exception {
    agency.script(200, response("42", 1));
    agency.script(200, response("43", 1));
    agency.script(200, response("44", 1));
    handler = handler();
    distributeThread.start();

    distributeThread.hold();
    handler.receive();
    assertTrue(distributeThread.awaitStarted(), "The first distribute must be in flight");
    handler.receive();
    handler.receive();
    distributeThread.release();

    // One in flight plus at most one coalesced follow-up. Without nudgePending this would be three.
    assertTrue(distributeThread.await(2, 10));
    Thread.sleep(500);
    assertTrue(distributeThread.count() <= 2, "Expected at most 2 distributes, saw " + distributeThread.count());
  }

  @Test
  public void daemonBlocksUntilShutdownReleasesIt() throws Exception {
    agency.script(304, "");
    handler = handler();

    Thread caller = new Thread(() -> handler.daemon(TrayState.HEALTHY), "daemon-caller");
    caller.start();

    assertTrue(distributeThread.await(1, 5), "The startup pass must run");
    assertTrue(caller.isAlive(), "daemon() must block rather than returning immediately");

    handler.shutdown();
    caller.join(10_000);

    Assert.assertFalse(caller.isAlive(), "shutdown() must release a caller blocked in daemon()");
  }

  @Test
  public void oneReceiveCycleStoringSeveralBriefsSendsOneNudge() throws Exception {
    agency.script(200, response(List.of("42", "43", "44"), briefJSON("42", 1), briefJSON("43", 1), briefJSON("44", 1)));
    handler = handler();
    distributeThread.start();

    handler.receive();

    assertTrue(distributeThread.await(1, 5));
    Thread.sleep(500);
    assertEquals(distributeThread.count(), 1, "Three Briefs in one cycle must coalesce into one nudge");
  }

  @BeforeMethod
  public void setUp() {
    agency = new FakeAgency();
    agency.start();
  }

  @Test
  public void syncOnceRunsReceiveThenDistributeAndSendsNoNudge() throws Exception {
    agency.script(200, response("42", 1));
    handler = handler();

    handler.receiveAndDistribute(false);

    assertEquals(store.latest("42").orElseThrow().version(), 1);
    assertEquals(distributeThread.count(), 1, "syncOnce distributes exactly once - the nudge is a no-op");
  }

  @AfterMethod
  public void tearDown() {
    if (handler != null) {
      handler.shutdown();
      handler = null;
    }

    agency.close();
  }

  private Handler handler() throws IOException {
    return handler(null);
  }

  private Handler handler(TokenWatcher watcher) throws IOException {
    HandlerConfig config = new HandlerConfig(locations().toString(), null, agency.url(), null, 3600, 3600);
    distributeThread = new CountingDistributeThread(config, store, new LocationScanner(config), new BriefPlanner(),
                                                    new LocationApplier(), new StateStore(base.resolve("state.json")));
    return new Handler(config, new AgencyClient(config.theAgencyURL(), new StubTokenSupplier("test-token")), store,
                       distributeThread, null, watcher);
  }

  /** Counts distribute calls so the nudge can be observed without adding production indirection. */
  private static class CountingDistributeThread extends DistributeThread {
    private final AtomicInteger count = new AtomicInteger();
    private volatile CountDownLatch hold = new CountDownLatch(0);
    private final Object monitor = new Object();
    private final AtomicBoolean shouldThrow = new AtomicBoolean();
    private final CountDownLatch started = new CountDownLatch(1);

    CountingDistributeThread(HandlerConfig config, FileBriefStore store, LocationScanner scanner, BriefPlanner planner,
                             LocationApplier applier, StateStore stateStore) {
      super(config, store, scanner, planner, applier, stateStore);
    }

    boolean await(int target, long timeout) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
      synchronized (monitor) {
        while (count.get() < target) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            return false;
          }

          monitor.wait(Math.max(1, remaining / 1_000_000));
        }
      }

      return true;
    }

    boolean awaitStarted() throws InterruptedException {
      return started.await(5, TimeUnit.SECONDS);
    }

    int count() {
      return count.get();
    }

    /** Blocks the next {@link #distribute(boolean)} call until {@link #release()} is called. */
    void hold() {
      hold = new CountDownLatch(1);
    }

    void release() {
      hold.countDown();
    }

    /** Makes the next {@link #distribute(boolean)} call throw after counting, to prove the guard survives it. */
    void throwOnce() {
      shouldThrow.set(true);
    }

    @Override
    public Summary distribute(boolean force) {
      started.countDown();
      try {
        hold.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      if (shouldThrow.compareAndSet(true, false)) {
        synchronized (monitor) {
          count.incrementAndGet();
          monitor.notifyAll();
        }

        throw new RuntimeException("Simulated distribute failure");
      }

      Summary summary = super.distribute(force);
      synchronized (monitor) {
        count.incrementAndGet();
        monitor.notifyAll();
      }

      return summary;
    }
  }
}

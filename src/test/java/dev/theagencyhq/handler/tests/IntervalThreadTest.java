/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import static org.testng.Assert.*;

/**
 * The nudge, interval, and shutdown mechanics on their own, with no Agency and no filesystem. Every test uses an
 * interval of an hour, so any run at all can only have come from a nudge.
 */
public class IntervalThreadTest extends BaseTest {
  private CountingThread thread;

  @Test
  public void aNudgeArrivingDuringARunTriggersOneMoreRun() throws Exception {
    thread = new CountingThread(3600);
    thread.hold();
    thread.start();
    thread.nudge();

    assertTrue(thread.awaitStarted(), "The first run must be in flight");

    // The signal lands with nobody parked. Only nudgePending keeps it from being lost.
    thread.nudge();
    thread.nudge();
    thread.release();

    assertTrue(thread.await(2), "A nudge sent mid-run must still cause another run");
    Thread.sleep(500);
    assertEquals(thread.count(), 2, "Two nudges during one run must coalesce into a single extra run");
  }

  @Test
  public void aNudgeWakesTheThreadWithoutWaitingOutTheInterval() throws Exception {
    thread = new CountingThread(3600);
    thread.start();

    thread.nudge();

    assertTrue(thread.await(1), "The nudge must wake the thread immediately");
  }

  @Test
  public void aNudgeSentBeforeTheThreadStartsIsNotLost() throws Exception {
    thread = new CountingThread(3600);

    thread.nudge();
    thread.start();

    assertTrue(thread.await(1), "A nudge before start() must survive into the first wait");
  }

  @Test
  public void aThrowingExecuteDoesNotKillTheLoop() throws Exception {
    thread = new CountingThread(3600);
    thread.start();

    thread.throwOnce();
    thread.nudge();
    assertTrue(thread.await(1), "The throwing run still counts as a run");

    thread.nudge();
    assertTrue(thread.await(2), "A throwing run must not end the loop");
  }

  @Test
  public void shutdownStopsTheThreadWithoutInterruptingIt() throws Exception {
    thread = new CountingThread(3600);
    thread.hold();
    thread.start();
    thread.nudge();
    assertTrue(thread.awaitStarted(), "The run must be in flight");

    thread.shutdown();
    assertTrue(thread.isAlive(), "shutdown() must not interrupt a run that is in flight");

    thread.release();
    thread.join(5000);

    assertFalse(thread.isAlive(), "The thread must stop once the in-flight run completes");
    assertFalse(thread.interrupted, "The in-flight run must never see an interrupt");
  }

  @Test
  public void theFirstRunWaitsOutTheIntervalRatherThanRunningImmediately() throws Exception {
    thread = new CountingThread(3600);

    thread.start();
    Thread.sleep(500);

    assertEquals(thread.count(), 0, "The interval is also the initial delay");
  }

  @AfterMethod
  public void tearDown() throws Exception {
    if (thread != null) {
      thread.release();
      thread.shutdown();
      thread.join(5000);
      thread = null;
    }
  }

  private static class CountingThread extends IntervalThread {
    volatile boolean interrupted;
    private final AtomicInteger count = new AtomicInteger();
    private volatile CountDownLatch hold = new CountDownLatch(0);
    private final long intervalSeconds;
    private final Object monitor = new Object();
    private final AtomicBoolean shouldThrow = new AtomicBoolean();
    private final CountDownLatch started = new CountDownLatch(1);

    CountingThread(long intervalSeconds) {
      super("interval-test");
      this.intervalSeconds = intervalSeconds;
    }

    boolean await(int target) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
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

    void hold() {
      hold = new CountDownLatch(1);
    }

    void release() {
      hold.countDown();
    }

    void throwOnce() {
      shouldThrow.set(true);
    }

    @Override
    protected void execute() {
      started.countDown();
      try {
        hold.await();
      } catch (InterruptedException e) {
        interrupted = true;
        Thread.currentThread().interrupt();
      }

      synchronized (monitor) {
        count.incrementAndGet();
        monitor.notifyAll();
      }

      if (shouldThrow.compareAndSet(true, false)) {
        throw new RuntimeException("Simulated cycle failure");
      }
    }

    @Override
    protected long intervalSeconds() {
      return intervalSeconds;
    }
  }
}

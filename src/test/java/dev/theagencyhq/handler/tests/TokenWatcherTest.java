/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.*;

/**
 * The watcher against a real filesystem and the real {@link TokenStore} writer, so the temp-file-and-move sequence
 * the daemon sees in production is what these tests see. The JDK's macOS WatchService polls every two seconds, so
 * every wait here allows well beyond that.
 */
public class TokenWatcherTest extends BaseTest {
  private static final long WAIT_MILLIS = 10_000;

  private final AtomicInteger adoptions = new AtomicInteger();
  private final AtomicInteger nudges = new AtomicInteger();
  private TokenStore store;
  private Path tokensFile;
  private TokenWatcher watcher;

  @Test
  public void aLogoutIsAChangeToo() throws Exception {
    store.store(new Tokens("access", "refresh"));
    watcher = start(() -> true);

    store.clear();

    assertTrue(await(nudges, 1), "Deleting the file must nudge");
  }

  @Test
  public void aWriteByAnotherProcessNudges() throws Exception {
    watcher = start(() -> true);

    store.store(new Tokens("written-by-login", "refresh"));

    assertTrue(await(nudges, 1), "The write must be seen and turned into a nudge");
  }

  @Test
  public void aWriteTheSupplierDoesNotAdoptIsSeenButNeverNudges() throws Exception {
    // This is the daemon's own refresh write: the file changed, the supplier already holds its contents
    watcher = start(() -> false);

    store.store(new Tokens("written-by-this-process", "refresh"));

    assertTrue(await(adoptions, 1), "The watcher must ask the supplier about the change");
    assertEquals(nudges.get(), 0, "A change the supplier did not adopt must not wake the receive loop");
  }

  @Test
  public void anUnrelatedFileInTheDirectoryIsIgnored() throws Exception {
    watcher = start(() -> true);

    Files.writeString(tokensFile.resolveSibling("handler.json"), "{}");
    store.store(new Tokens("access", "refresh"));

    assertTrue(await(nudges, 1));
    assertEquals(adoptions.get(), 1, "Only the token file's own events reach the supplier");
  }

  @AfterMethod
  public void cleanUp() {
    if (watcher != null) {
      watcher.stop();
    }
  }

  @BeforeMethod
  public void setUp() {
    // TestNG runs every method on one instance, so the counters must not carry over from the previous test
    adoptions.set(0);
    nudges.set(0);
    tokensFile = base.resolve("config/tokens.json");
    store = new TokenStore(tokensFile);
  }

  @Test
  public void stopEndsTheWatch() throws Exception {
    watcher = start(() -> true);
    watcher.stop();

    store.store(new Tokens("access", "refresh"));

    // Longer than the macOS polling interval, so a watcher that kept running would have reported by now
    Thread.sleep(3_000);
    assertEquals(nudges.get(), 0, "A stopped watcher must not nudge");
  }

  @Test
  public void theDirectoryIsCreatedWhenMissing() throws Exception {
    // A fresh install has no config directory until the first login writes it, and a watch needs one to register on
    tokensFile = base.resolve("missing/tokens.json");
    store = new TokenStore(tokensFile);
    watcher = start(() -> true);

    assertTrue(Files.isDirectory(tokensFile.getParent()));

    store.store(new Tokens("access", "refresh"));
    assertTrue(await(nudges, 1));
  }

  private boolean await(AtomicInteger counter, int target) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (counter.get() < target && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }

    return counter.get() >= target;
  }

  private TokenWatcher start(BooleanSupplier adopted) {
    TokenWatcher started = new TokenWatcher(tokensFile, () -> {
      adoptions.incrementAndGet();
      return adopted.getAsBoolean();
    });
    started.start(nudges::incrementAndGet);
    return started;
  }
}

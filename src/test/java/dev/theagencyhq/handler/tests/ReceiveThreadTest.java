/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.ReceiveThread;
import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.brief.Brief;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.location.LocationScanner;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class ReceiveThreadTest extends BaseTest {
  private FakeAgency agency;
  private AtomicInteger nudges;
  private ReceiveThread receiveThread;

  @Test
  public void aSecondForbiddenCycleSendsNoFurtherNudge() {
    store.store(brief("42", 1, file(".claude/a.md", "x")));
    agency.script(403, "");
    agency.script(403, "");

    receive();
    receive();

    assertTrue(store.revoked("42"));
    assertEquals(nudges.get(), 1, "A prolonged 403 must not re-signal every cycle");
  }

  @Test
  public void checksumMismatchStoresNothingAndLeavesThePreviousVersionLive() {
    store.store(brief("42", 1, file(".claude/a.md", "content")));
    agency.script(200, response("42", 2, file(".claude/a.md", "tampered", sha256("something else"))));

    receive();

    assertEquals(store.latest("42").orElseThrow().version(), 1);
    assertEquals(nudges.get(), 0, "Nothing changed in the store, so no nudge");
  }

  @Test
  public void currentVersionsAreSentFromTheStore() {
    store.store(brief("42", 73, file(".claude/a.md", "x")));
    agency.script(304, "");

    receive();

    String body = agency.requestBodies().getFirst();
    assertTrue(body.contains("\"organizationId\":\"42\""), "Body was: " + body);
    assertTrue(body.contains("\"version\":73"), "Body was: " + body);
  }

  @Test
  public void failedRequestLeavesTheStoreAloneAndSendsNoNudge() {
    store.store(brief("42", 1, file(".claude/a.md", "x")));
    agency.script(500, "");

    receive();

    assertEquals(store.latest("42").orElseThrow().version(), 1);
    assertEquals(nudges.get(), 0);
  }

  @Test
  public void forbiddenRevokesEveryStoredOrganizationAndNudges() {
    store.store(brief("42", 1, file(".claude/a.md", "x")));
    store.store(brief("43", 1, file(".claude/a.md", "y")));
    agency.script(403, "");

    receive();

    assertTrue(store.revoked("42"));
    assertTrue(store.revoked("43"));
    assertEquals(nudges.get(), 1);
  }

  @Test
  public void forbiddenWithAnEmptyStoreSendsNoNudge() {
    agency.script(403, "");

    receive();

    assertEquals(nudges.get(), 0);
  }

  @Test
  public void notModifiedSendsNoNudge() {
    agency.script(304, "");

    receive();

    assertEquals(nudges.get(), 0);
  }

  @Test
  public void organizationAbsentFromTheEntitledSetIsMarkedRevoked() {
    store.store(brief("42", 1, file(".claude/a.md", "x")));
    store.store(brief("43", 1, file(".claude/a.md", "y")));
    // The response entitles only 42, so 43 has been revoked
    agency.script(200, response("42", 2, file(".claude/a.md", "x2")));

    receive();

    Assert.assertFalse(store.revoked("42"));
    assertTrue(store.revoked("43"));
    assertEquals(nudges.get(), 1);
  }

  @BeforeMethod
  public void setUp() throws IOException {
    nudges = new AtomicInteger();
    agency = new FakeAgency();
    agency.start();

    // Neither thread is started - execute() is called directly, and nudge() is counted rather than delivered
    HandlerConfig config = new HandlerConfig(base.toString(), null, agency.url(), null, 3600, 3600);
    DistributeThread distributeThread = new DistributeThread(config, store, new LocationScanner(config),
                                                             new BriefPlanner(), new LocationApplier()) {
      @Override
      public void nudge() {
        nudges.incrementAndGet();
      }
    };
    receiveThread = new ReceiveThread(config, new AgencyClient(agency.url(), new StubTokenSupplier("token")), store,
                                      distributeThread);
  }

  @AfterMethod
  public void tearDown() {
    agency.close();
  }

  @Test
  public void updatedStoresTheBriefAndNudgesExactlyOnce() throws IOException {
    agency.script(200, response("42", 73, file(".claude/a.md", "For Claude")));

    receive();

    assertEquals(store.latest("42").orElseThrow().version(), 73);
    assertEquals(nudges.get(), 1, "One nudge per receive cycle that changed the store, not one per Brief");
    assertTrue(Files.readString(storeRoot().resolve("42/73/brief.json")).contains("\"version\":73"));
  }

  private void receive() {
    // execute(), not receive(): the nudge rule lives in execute() and that is what the interval loop runs
    receiveThread.execute();
  }
}

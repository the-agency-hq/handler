/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.Handler;
import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.cli.HandlerCLI;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.config.HandlerPaths;
import dev.theagencyhq.handler.location.LocationScanner;

import static org.testng.Assert.*;

public class HandlerCLITest extends BaseTest {
  private FakeAgency agency;
  private ByteArrayOutputStream output;

  @Test
  public void helpAndVersionExitZero() throws IOException {
    assertEquals(cli().run("help"), 0);
    assertEquals(cli().run("--version"), 0);
    assertTrue(output.toString().contains("handler"), "Output was: " + output);
  }

  @BeforeMethod
  public void setUp() {
    output = new ByteArrayOutputStream();
    agency = new FakeAgency();
    agency.start();
  }

  @Test
  public void statusNamesEveryLocationAndItsStateAndWritesNothing() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    Path orphan = location("orphan", "999");

    assertEquals(cli().run("status"), 0);

    String printed = output.toString();
    assertTrue(printed.contains(location.toString()), "Output was: " + printed);
    assertTrue(printed.contains("changed"), "Output was: " + printed);
    assertTrue(printed.contains(orphan.toString()), "Output was: " + printed);
    assertTrue(printed.contains("no brief"), "Output was: " + printed);

    // A pure read - status must not bootstrap a manifest
    assertFalse(Files.exists(location.resolve(".handler-manifest")));
    assertFalse(Files.exists(orphan.resolve(".handler-manifest")));
  }

  @Test
  public void statusNeverPrintsTheToken() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    location("app", "42");

    cli().run("status");

    assertFalse(output.toString().contains("super-secret"), "The token must never be printed");
    assertTrue(output.toString().contains("accessToken"), "Output was: " + output);
  }

  @Test
  public void statusReportsBothUnchangedAndConflictStates() throws IOException {
    // Store "43" only AFTER the sync below: ReceiveTask treats a briefing response's organizationIds as
    // authoritative and revokes (then purges, with no Location yet to defer it) anything already stored that the
    // response omits - storing "43" first would have it revoked-and-purged out from under this test by "sync".
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    Path unchanged = location("applied", "42");
    assertEquals(cli().run("sync"), 0, "Only the clean Location exists at this point");

    store.store(brief("43", 1, file(".claude/a.md", "alpha")));
    Path conflicted = location("conflicted", "43");
    Files.createDirectories(conflicted.resolve(".claude"));
    Files.writeString(conflicted.resolve(".claude/a.md"), "unmanaged");

    assertEquals(cli().run("status"), 0);

    String printed = output.toString();
    assertTrue(printed.contains("  " + unchanged + "  unchanged"), "Output was: " + printed);
    assertTrue(printed.contains("  " + conflicted + "  conflict"), "Output was: " + printed);
  }

  @Test
  public void syncExitsOneWhenALocationConflicts() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "unmanaged");

    assertEquals(cli().run("sync"), 1);
  }

  @Test
  public void syncExitsZeroAndForceAdoptsAConflict() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "unmanaged");

    assertEquals(cli().run("sync"), 1);
    assertEquals(cli().run("sync", "--force"), 0);
    assertEquals(Files.readString(location.resolve(".claude/a.md")), "alpha");
  }

  @AfterMethod
  public void tearDown() {
    agency.close();
  }

  @Test
  public void unknownSubcommandExitsOne() throws IOException {
    assertEquals(cli().run("frobnicate"), 1);
  }

  private HandlerCLI cli() throws IOException {
    HandlerConfig config = new HandlerConfig(locations().toString(), null, agency.url(), "super-secret",
                                            null, 3600, 3600);
    HandlerPaths paths = new HandlerPaths(base.resolve("handler.json"), storeRoot(),
                                          base.resolve("handler.log"));
    LocationScanner scanner = new LocationScanner(config);
    BriefPlanner planner = new BriefPlanner();
    LocationApplier applier = new LocationApplier();
    DistributeThread distributeThread = new DistributeThread(config, store, scanner, planner, applier);
    Handler handler = new Handler(config, new AgencyClient(config.theAgencyURL(), config::accessToken), store,
                                  distributeThread);

    return new HandlerCLI(paths, config, store, scanner, planner, applier, handler, new PrintStream(output, true));
  }
}

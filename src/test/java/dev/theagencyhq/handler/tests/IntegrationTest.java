/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.apply.Manifest;

import static org.testng.Assert.*;

public class IntegrationTest extends BaseTest {
  private FakeAgency agency;

  @Test
  public void agencyUnreachableStillDistributesFromTheStore() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    agency.close();     // every request now fails

    handler().receiveAndDistribute(false);

    assertEquals(Files.readString(location.resolve(".claude/a.md")), "alpha");
  }

  @Test
  public void crashMidApplyConvergesOnTheNextCycle() throws IOException {
    agency.script(200, response("42", 1, file(".claude/rules/a.md", "alpha")));
    Path location = location("app", "42");

    // A manifest listing entries whose files were never written - exactly what a kill between the flushed append and
    // the file write leaves behind
    Files.writeString(location.resolve(Manifest.FILENAME), "0.1.0\n.claude/\n.claude/rules/\n.claude/rules/a.md\n");

    DistributeThread.Summary summary = handler().receiveAndDistribute(false);

    assertTrue(summary.clean(), "Recovery must not report a conflict or a failure: " + summary);
    assertEquals(Files.readString(location.resolve(".claude/rules/a.md")), "alpha");
  }

  @Test
  public void emptyBriefTearsDownButLeavesTheManifestExcluded() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    agency.script(200, response("42", 2));
    Path location = location("app", "42");

    handler().receiveAndDistribute(false);
    assertTrue(Files.exists(location.resolve(".claude/a.md")));

    handler().receiveAndDistribute(false);

    assertFalse(Files.exists(location.resolve(".claude")), "The teardown removes everything it created");
    assertFalse(Files.exists(location.resolve(".gitignore")), "The Handler never writes the team's .gitignore");
    assertTrue(Files.readAllLines(location.resolve(".git/info/exclude")).contains(Manifest.FILENAME),
               "The manifest survives a teardown, so its exclude line must too");
  }

  @Test
  public void gitExcludeCarriesEveryManagedFileAndIsCleanedUp() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    agency.script(200, response("42", 2, file(".claude/b.md", "bravo")));

    Path location = location("app", "42");

    // First response from the agency (version 1)
    handler().receiveAndDistribute(false);
    assertEquals(Files.readAllLines(location.resolve(".git/info/exclude")),
                 List.of(Manifest.FILENAME, ".claude/a.md", Manifest.STAGING_DIRECTORY + "/"));

    // Second response from the agency (version 2)
    handler().receiveAndDistribute(false);
    assertEquals(Files.readAllLines(location.resolve(".git/info/exclude")),
                 List.of(Manifest.FILENAME, Manifest.STAGING_DIRECTORY + "/", ".claude/b.md"),
                 "The dropped file's line must be removed and the new one added");
  }

  @Test
  public void notModifiedStillPopulatesANewlyCreatedLocation() throws IOException {
    store.store(brief("42", 7, file(".claude/a.md", "alpha")));
    agency.script(304, "");

    Path location = location("brand-new", "42");
    FileTime storeTime = Files.getLastModifiedTime(storeRoot().resolve("42/7/brief.json"));

    handler().receiveAndDistribute(false);

    assertEquals(Files.readString(location.resolve(".claude/a.md")), "alpha");
    assertEquals(Files.getLastModifiedTime(storeRoot().resolve("42/7/brief.json")), storeTime, "A 304 performs no store writes");
  }

  @Test
  public void revocationTearsDownEveryLocationThenPurgesTheStore() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    agency.script(200, "{\"organizationIds\":[],\"briefs\":[]}");

    Path one = location("one", "42");
    Path two = location("two", "42");

    handler().receiveAndDistribute(false);
    assertTrue(Files.exists(one.resolve(".claude/a.md")));
    assertTrue(Files.exists(two.resolve(".claude/a.md")));

    handler().receiveAndDistribute(false);

    assertFalse(Files.exists(one.resolve(".claude")));
    assertFalse(Files.exists(two.resolve(".claude")));
    assertFalse(Files.exists(storeRoot().resolve("42")), "The store entry is purged after a clean teardown");
  }

  @BeforeMethod
  public void setUp() {
    agency = new FakeAgency();
    agency.start();
  }

  @AfterMethod
  public void tearDown() {
    agency.close();
  }

  @Test
  public void unchangedInputTouchesNothingAcrossTwoCycles() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    agency.script(304, "");

    Path location = location("app", "42");

    handler().receiveAndDistribute(false);

    Path applied = location.resolve(".claude/a.md");
    Path manifest = location.resolve(Manifest.FILENAME);
    FileTime fileTime = Files.getLastModifiedTime(applied);
    FileTime manifestTime = Files.getLastModifiedTime(manifest);

    handler().receiveAndDistribute(false);

    assertEquals(Files.getLastModifiedTime(applied), fileTime);
    assertEquals(Files.getLastModifiedTime(manifest), manifestTime);
  }

  @Test
  public void versionBumpReplacesFilesAndRemovesDroppedOnes() throws IOException {
    agency.script(200, response("42", 1, file(".claude/old.md", "old"), file(".claude/both.md", "first")));
    agency.script(200, response("42", 2, file(".claude/both.md", "second"), file(".claude/new.md", "new")));

    Path location = location("app", "42");

    handler().receiveAndDistribute(false);
    handler().receiveAndDistribute(false);

    assertEquals(Files.readString(location.resolve(".claude/both.md")), "second");
    assertEquals(Files.readString(location.resolve(".claude/new.md")), "new");
    assertFalse(Files.exists(location.resolve(".claude/old.md")));
    assertEquals(store.latest("42").orElseThrow().version(), 2);
  }

  private Handler handler() throws IOException {
    HandlerConfig config = new HandlerConfig(locations().toString(), null, agency.url(), null, 3600, 3600);
    LocationScanner scanner = new LocationScanner(config);
    DistributeThread distributeThread = new DistributeThread(config, store, scanner, new BriefPlanner(),
                                                             new LocationApplier());
    return new Handler(config, new AgencyClient(config.theAgencyURL(), new StubTokenSupplier("test-token")), store,
                       distributeThread);
  }
}

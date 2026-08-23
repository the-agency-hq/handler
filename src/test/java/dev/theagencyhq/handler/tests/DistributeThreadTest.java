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

public class DistributeThreadTest extends BaseTest {

  @Test
  public void anInvalidPlanFailsOnlyItsOwnLocation() throws IOException {
    // An absolute path will be rejected by the planner (not by the store), which fails the whole plan for that Location
    store.store(brief("42", 1, file("/etc/passwd", "evil")));
    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
    location("broken", "42");

    // Store a good brief
    store.store(brief("43", 1, file(".claude/a.md", "alpha")));
    Path healthy = location("healthy", "43");

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.failed(), 1);
    assertEquals(summary.applied(), 1);
    assertEquals(Files.readString(healthy.resolve(".claude/a.md")), "alpha");
  }

  @Test
  public void everyMatchingLocationIsUpdatedIndependently() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path one = location("one", "42");
    Path two = location("nested/two", "42");

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.applied(), 2);
    assertEquals(Files.readString(one.resolve(".claude/a.md")), "alpha");
    assertEquals(Files.readString(two.resolve(".claude/a.md")), "alpha");
  }

  @Test
  public void forceAdoptsAnUnmanagedFileAtAPlannedPath() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "mine");

    assertEquals(distribute(false).conflict(), 1, "Without force the Location is skipped, never overwritten");
    assertEquals(Files.readString(location.resolve(".claude/a.md")), "mine");

    DistributeThread.Summary summary = distribute(true);

    assertEquals(summary, new DistributeThread.Summary(1, 0, 0, 0));
    assertEquals(Files.readString(location.resolve(".claude/a.md")), "alpha");
    assertTrue(Files.readAllLines(location.resolve(Manifest.FILENAME)).contains(".claude/a.md"),
               "An adopted file becomes managed, so a later teardown has to know to remove it");
  }

  @Test
  public void forceAdoptsAtEveryLocationNotJustOne() throws IOException {
    // Spec section 10: `handler sync --force` adopts at EVERY Location. There is no way to select one.
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path one = location("one", "42");
    Path two = location("two", "42");
    for (Path location : List.of(one, two)) {
      Files.createDirectories(location.resolve(".claude"));
      Files.writeString(location.resolve(".claude/a.md"), "mine");
    }

    DistributeThread.Summary summary = distribute(true);

    assertEquals(summary, new DistributeThread.Summary(2, 0, 0, 0));
    assertEquals(Files.readString(one.resolve(".claude/a.md")), "alpha");
    assertEquals(Files.readString(two.resolve(".claude/a.md")), "alpha");
  }

  @Test
  public void forceNeverAdoptsAPreExistingDirectoryIntoTheManifest() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    // The developer's own directory. The unmanaged file inside it is what routes this through the adopt path.
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "mine");

    assertEquals(distribute(true), new DistributeThread.Summary(1, 0, 0, 0));

    List<String> manifest = Files.readAllLines(location.resolve(Manifest.FILENAME));
    assertTrue(manifest.contains(".claude/a.md"), "The adopted file is the Handler's now");
    assertFalse(manifest.contains(".claude/"), "A directory the developer created must never become a teardown candidate, not even under force");
  }

  @Test
  public void forceReplacesADirectorySittingAtAManagedFilePath() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    distribute(false);

    // The developer replaced the managed file with a non-empty directory. Teardown cannot delete that - it would
    // throw DirectoryNotEmptyException - so it retains the entry and leaves the replacement for the write step.
    // Without force the Location would report conflict on every cycle forever, which is what force exists to break.
    Files.delete(location.resolve(".claude/a.md"));
    Files.createDirectories(location.resolve(".claude/a.md/nested"));
    Files.writeString(location.resolve(".claude/a.md/nested/junk.txt"), "junk");

    assertEquals(distribute(false).conflict(), 1, "A directory at a managed file path is a conflict");

    DistributeThread.Summary summary = distribute(true);

    assertEquals(summary, new DistributeThread.Summary(1, 0, 0, 0));
    assertTrue(Files.isRegularFile(location.resolve(".claude/a.md")));
    assertEquals(Files.readString(location.resolve(".claude/a.md")), "alpha");
  }

  @Test
  public void forceReplacesASymlinkAtAPlannedDirectoryInsteadOfWritingThroughIt() throws IOException {
    // The section 8.6 symlink obligation. force adopts what is in the way, but "in the way" means inside the
    // Location - adopting a link must never turn into a write at wherever that link points.
    store.store(brief("42", 1, file("docs/secret.md", "alpha")));
    Path location = location("app", "42");
    Path outside = Files.createDirectories(base.resolve("outside"));
    Files.writeString(outside.resolve("secret.md"), "not the Handler's");
    Files.createSymbolicLink(location.resolve("docs"), outside);

    assertEquals(distribute(false).conflict(), 1, "A symlink at a planned path is an unmanaged entry");

    DistributeThread.Summary summary = distribute(true);

    assertEquals(summary, new DistributeThread.Summary(1, 0, 0, 0));
    assertFalse(Files.isSymbolicLink(location.resolve("docs")), "The link itself is replaced, never followed");
    assertEquals(Files.readString(location.resolve("docs/secret.md")), "alpha");
    assertEquals(Files.readString(outside.resolve("secret.md")), "not the Handler's", "Adopting a link must never write through it to somewhere outside the Location");
  }

  @Test
  public void locationForAnUnknownOrganizationIsSkippedWithoutTeardown() throws IOException {
    Path location = location("orphan", "999");
    Files.writeString(location.resolve("mine.md"), "keep");

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary, new DistributeThread.Summary(0, 0, 0, 0));
    assertTrue(Files.exists(location.resolve("mine.md")), "Nothing to tear down means nothing is touched");
  }

  @Test
  public void everyLocationInALargeSetIsApplied() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    for (int i = 0; i < 20; i++) {
      location("app" + i, "42");
    }

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.applied(), 20, "Every Location in the scan must be applied and counted");
  }

  @Test
  public void oneConflictingLocationDoesNotStopTheOthers() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path conflicted = location("conflicted", "42");
    Files.createDirectories(conflicted.resolve(".claude"));
    Files.writeString(conflicted.resolve(".claude/a.md"), "mine");
    Path clean = location("clean", "42");

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.conflict(), 1);
    assertEquals(summary.applied(), 1);
    assertEquals(Files.readString(conflicted.resolve(".claude/a.md")), "mine");
    assertEquals(Files.readString(clean.resolve(".claude/a.md")), "alpha");
  }

  @Test
  public void revokedOrganizationIsTornDownThenPurged() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    distribute(false);
    assertTrue(Files.exists(location.resolve(".claude/a.md")));

    store.markRevoked("42");
    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.applied(), 1);
    assertFalse(Files.exists(location.resolve(".claude")), "Revocation tears the Location down");
    assertFalse(Files.exists(storeRoot().resolve("42")), "Then the store entry is purged");
    assertFalse(store.latest("42").isPresent());
  }

  @Test
  public void revokedOrganizationSurvivesAnUnreadableStartDirectory() throws IOException {
    // An IOException reading the start directory itself means the scan is incomplete, not "nothing exists" - the
    // same class of hazard as an unreadable manifest, but discovered by LocationScanner instead of LocationApplier.
    if (runningAsRoot()) {
      fail("Running as root bypasses POSIX permission checks, so an unreadable start directory cannot be simulated");
    }

    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    location("app", "42");
    distribute(false);
    store.markRevoked("42");

    Path locationsRoot = locations();
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(locationsRoot);
    Files.setPosixFilePermissions(locationsRoot, PosixFilePermissions.fromString("---------"));
    try {
      DistributeThread.Summary summary = distribute(false);

      assertEquals(summary, new DistributeThread.Summary(0, 0, 0, 0));
      assertTrue(Files.exists(storeRoot().resolve("42")), "The purge must be deferred while the start directory is unreadable");
    } finally {
      Files.setPosixFilePermissions(locationsRoot, original);
    }
  }

  @Test
  public void revokedOrganizationWithAnUnreadableManifestDefersThePurge() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");

    // An unknown manifest format means the Handler cannot know what it created there, so it must neither tear down
    // nor purge - the Brief has to survive so a later cycle can retry once a human fixes the manifest.
    Files.writeString(location.resolve(".handler-manifest"), "9.0.0\n.claude/\n");
    store.markRevoked("42");

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.conflict(), 1);
    assertTrue(Files.exists(storeRoot().resolve("42")), "The Brief needed for a later teardown must survive a deferred purge");
  }

  @Test
  public void revokedOrganizationWithNothingEverSyncedPurgesImmediately() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");

    // Unmanaged content the Handler never created. There is no manifest, so there is nothing to tear down and
    // nothing to conflict with - the correct outcome is to leave the developer's file alone and purge the store.
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "unmanaged");
    store.markRevoked("42");

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary, new DistributeThread.Summary(0, 1, 0, 0));
    assertEquals(Files.readString(location.resolve(".claude/a.md")), "unmanaged");
    assertFalse(Files.exists(storeRoot().resolve("42")), "Nothing to clean up means the purge can proceed");
    assertFalse(store.latest("42").isPresent());
  }

  @Test
  public void secondPassOverUnchangedLocationsReportsUnchanged() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    location("app", "42");
    distribute(false);

    assertEquals(distribute(false), new DistributeThread.Summary(0, 1, 0, 0));
  }

  private DistributeThread.Summary distribute(boolean force) throws IOException {
    HandlerConfig config = new HandlerConfig(locations().toString(), null, null, null, 0, 0);
    return new DistributeThread(config, store, new LocationScanner(config), new BriefPlanner(), new LocationApplier())
        .distribute(force);
  }
}

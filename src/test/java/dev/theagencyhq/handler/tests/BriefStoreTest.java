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

public class BriefStoreTest extends BaseTest {

  @Test
  public void aLeftoverTemporaryFileFromACrashedStoreIsDeleted() throws IOException {
    // Write out a failed sync temp file before we store. This simulates a previous store that failed.
    Path partial = storeRoot().resolve("incoming/brief.json.tmp-" + UUID.randomUUID());
    Files.createDirectories(storeRoot().resolve("incoming"));
    Files.writeString(partial, "{ partial");
    Files.setLastModifiedTime(partial, FileTime.from(Instant.now().minus(Duration.ofHours(1))));

    store.store(brief("42", 1));

    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
    assertEquals(Files.readAllBytes(storeRoot().resolve("42/1/brief.json")), brief("42", 1).rawBytes());
    assertFalse(Files.isRegularFile(partial));
  }

  @Test
  public void aLeftoverTemporaryFileFromACrashedManualSyncIsIgnored() throws IOException {
    store.store(brief("42", 1));

    // Write out a failed sync temp file after we store. This simulates a `handler sync` that failed.
    Path partial = storeRoot().resolve("incoming/brief.json.tmp-" + UUID.randomUUID());
    Files.writeString(partial, "{ partial");
    Files.setLastModifiedTime(partial, FileTime.from(Instant.now().minus(Duration.ofHours(1))));

    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
    assertEquals(Files.readAllBytes(storeRoot().resolve("42/1/brief.json")), brief("42", 1).rawBytes());
    assertTrue(Files.isRegularFile(partial));
  }

  @Test
  public void aRecentTemporaryFileIsLeftAloneBecauseAnotherProcessMayBeWritingIt() throws IOException {
    store.store(brief("42", 1));
    // Every subcommand runs against the same store with no IPC, so `handler sync` can be mid-write while the daemon
    // stores. Deleting that file would fail the other process's move.
    Path inFlight = storeRoot().resolve("42/1/brief.json.tmp-" + UUID.randomUUID());
    Files.writeString(inFlight, "{ partial");

    store.store(brief("42", 2));

    assertTrue(Files.isRegularFile(inFlight), "A temp file younger than the age bound must survive the sweep");
  }

  @Test
  public void allCurrentReturnsTheLatestPerOrgAndExcludesRevoked() throws IOException {
    store.store(brief("42", 1));
    store.store(brief("42", 2));
    store.store(brief("43", 7));
    store.markRevoked("43");

    List<String> ids = store.allCurrent().stream().map(StoredBrief::organizationId).toList();
    assertEquals(ids, List.of("42"));
    assertEquals(store.latest("42").orElseThrow().brief().version(), 2);
  }

  @Test
  public void latestIgnoresNonNumericDirectoryNames() throws IOException {
    store.store(brief("42", 3));
    Files.createDirectories(storeRoot().resolve("42/not-a-version"));

    assertEquals(store.latest("42").orElseThrow().brief().version(), 3);
  }

  @Test
  public void latestSkipsADocumentThatFailsToParse() throws IOException {
    store.store(brief("42", 1));
    Files.createDirectories(storeRoot().resolve("42/2"));
    Files.writeString(storeRoot().resolve("42/2/brief.json"), "{ this is not json");

    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
  }

  @Test
  public void latestSkipsAVersionWhoseDocumentDisagreesWithItsPath() throws IOException {
    store.store(brief("42", 1));
    Files.createDirectories(storeRoot().resolve("42/9"));
    Files.writeString(storeRoot().resolve("42/9/brief.json"), brief("42", 8).raw());

    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
  }

  @Test
  public void latestSkipsAVersionWhoseDocumentNamesADifferentOrganization() throws IOException {
    store.store(brief("42", 1));
    Files.createDirectories(storeRoot().resolve("42/2"));
    Files.writeString(storeRoot().resolve("42/2/brief.json"), brief("99", 2).raw());

    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
  }

  @Test
  public void latestSkipsAnIncompleteVersionDirectory() throws IOException {
    store.store(brief("42", 1));
    Files.createDirectories(storeRoot().resolve("42/2"));       // created but brief.json never landed

    // The previous version stays live - this is what makes the lock-free handoff safe
    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
  }

  @Test
  public void latestSortsVersionsNumericallyNotLexicographically() throws IOException {
    store.store(brief("42", 9));
    store.store(brief("42", 10));

    // Lexicographic ordering would rank "9" above "10"
    assertEquals(store.latest("42").orElseThrow().brief().version(), 10);
  }

  @Test
  public void purgeRemovesTheOrganizationEntirely() throws IOException {
    store.store(brief("42", 1));
    store.markRevoked("42");

    store.purge("42");

    assertFalse(Files.exists(storeRoot().resolve("42")));
    assertEquals(store.organizationIds(), Set.of());
  }

  @Test
  public void revocationSurvivesAFreshStoreInstance() throws IOException {
    FileBriefStore first = store();
    first.store(brief("42", 1));
    first.markRevoked("42");

    assertTrue(store().revoked("42"), "Revocation must be persisted, not held in memory");
  }

  @Test
  public void storeIsAtomicAndReplacesACorruptDocument() throws IOException {
    store.store(brief("42", 73));
    Path document = storeRoot().resolve("42/73/brief.json");
    Files.writeString(document, "{ truncated");

    store.store(brief("42", 73));

    assertEquals(Files.readString(document), brief("42", 73).raw());
    assertEquals(Files.getPosixFilePermissions(document), PosixFilePermissions.fromString("rw-------"));
    try (Stream<Path> entries = Files.list(storeRoot().resolve("incoming"))) {
      assertEquals(entries.count(), 0, "No temp file may be left behind");
    }
  }

  @Test
  public void storeSweepsStaleTemporaryFilesFromIncoming() throws IOException {
    store.store(brief("42", 1));
    store.store(brief("43", 1));
    Path targetVersion = stale(storeRoot().resolve("incoming/brief.json.tmp-" + UUID.randomUUID()));
    Path olderVersion = stale(storeRoot().resolve("incoming/brief.json.tmp-" + UUID.randomUUID()));
    Path otherOrganization = stale(storeRoot().resolve("incoming/brief.json.tmp-" + UUID.randomUUID()));

    // Storing version 2 must also clear version 1's litter - a crash there would otherwise leave it forever,
    // because no version is ever pruned
    store.store(brief("42", 2));

    assertFalse(Files.exists(targetVersion));
    assertFalse(Files.exists(olderVersion));
    assertFalse(Files.exists(otherOrganization));
    assertEquals(store.latest("42").orElseThrow().brief().version(), 2);
    assertEquals(Files.readAllBytes(storeRoot().resolve("42/1/brief.json")), brief("42", 1).rawBytes());
  }

  @Test
  public void storeWritesTheExactWireBytes() throws IOException {
    Brief brief = brief("42", 73);
    store.store(brief);

    assertEquals(Files.readAllBytes(storeRoot().resolve("42/73/brief.json")), brief.rawBytes());
  }

  /** Writes a partial temp file and backdates it past the sweep's age bound. */
  private Path stale(Path temporary) throws IOException {
    Files.writeString(temporary, "{ partial");
    Files.setLastModifiedTime(temporary, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
    return temporary;
  }

  /**
   * A second store over the same root. Only revocation persistence needs one — every other test uses the inherited
   * {@code store}.
   */
  private FileBriefStore store() throws IOException {
    return new FileBriefStore(storeRoot());
  }
}

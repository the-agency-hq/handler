/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;
import java.nio.file.Files;

import dev.theagencyhq.handler.apply.*;

import static org.testng.Assert.*;

public class ManifestTest extends BaseTest {
  private Path manifestFile;

  @Test
  public void aZeroLengthManifestIsRestartedRatherThanTreatedAsValid() throws IOException {
    Files.writeString(manifestFile, "");

    Manifest manifest = new FileManifest(manifestFile);

    // Indistinguishable from one the Handler never finished creating, so it gets the version line back
    assertEquals(Files.readAllLines(manifestFile), List.of("0.1.0"));
    assertEquals(manifest.entries(), List.of());
  }

  @Test
  public void appendIsVisibleOnDiskBeforeItReturns() throws IOException {
    Manifest manifest = new FileManifest(manifestFile);
    manifest.append(new Manifest.Entry(Path.of(".claude"), true));

    // Honest scope: this proves the bytes left the process, not that they reached the platter. Real durability needs
    // SYNC plus force(true), which no black-box test can observe without crash injection — verify those by reading
    // FileManifest.append, not by trusting this test.
    assertEquals(Files.readAllLines(manifestFile), List.of("0.1.0", ".claude/"));
  }

  @Test
  public void clearTruncatesToTheVersionLine() throws IOException {
    Manifest manifest = new FileManifest(manifestFile);
    manifest.append(new Manifest.Entry(Path.of(".claude"), true));
    manifest.append(new Manifest.Entry(Path.of(".claude/a.md"), false));

    manifest.clear();

    assertEquals(Files.readAllLines(manifestFile), List.of("0.1.0"));
    assertEquals(manifest.entries(), List.of());
  }

  @Test
  public void missingManifestIsCreatedWithOnlyTheVersionLine() throws IOException {
    Manifest manifest = new FileManifest(manifestFile);

    assertEquals(Files.readAllLines(manifestFile), List.of("0.1.0"));
    assertEquals(manifest.entries(), List.of());
  }

  @Test
  public void peekHasNoSideEffectsWhatsoever() throws IOException {
    // peek backs the read-only status command, so it must never create or rewrite anything
    assertEquals(FileManifest.peek(manifestFile), List.of());
    assertFalse(Files.exists(manifestFile), "peek must not create a missing manifest");

    Files.writeString(manifestFile, "");
    assertEquals(FileManifest.peek(manifestFile), List.of());
    assertEquals(Files.size(manifestFile), 0, "peek must not rewrite a zero-length manifest");

    Files.writeString(manifestFile, "0.1.0\n.claude/\n.claude/a.md\n");
    assertEquals(FileManifest.peek(manifestFile), List.of(new Manifest.Entry(Path.of(".claude"), true),
        new Manifest.Entry(Path.of(".claude/a.md"), false)));
    assertEquals(Files.readString(manifestFile), "0.1.0\n.claude/\n.claude/a.md\n");
  }

  @Test
  public void reverseOrderIsAlwaysASafeTeardownOrder() {
    Manifest manifest = new FileManifest(manifestFile);
    List.of(
            new Manifest.Entry(Path.of(".claude"), true),
            new Manifest.Entry(Path.of(".claude/skills"), true),
            new Manifest.Entry(Path.of(".claude/skills/one"), true),
            new Manifest.Entry(Path.of(".claude/skills/one/SKILL.md"), false)
        )
        .forEach(manifest::append);

    // Reload from disk rather than reading the in-memory list. Any append-only list trivially preserves insertion
    // order, so asserting against `manifest.entries()` would test the fixture rather than FileManifest's parsing.
    List<Manifest.Entry> reversed = new FileManifest(manifestFile).entries().reversed();

    // A directory never precedes its own contents in reverse order
    for (int i = 0; i < reversed.size(); i++) {
      for (int j = i + 1; j < reversed.size(); j++) {
        assertFalse(reversed.get(j).path().startsWith(reversed.get(i).path()), "[" + reversed.get(j).path() + "] must be torn down before [" + reversed.get(i).path() + "]");
      }
    }
  }

  @Test
  public void roundTripsThroughAFreshInstance() {
    Manifest manifest = new FileManifest(manifestFile);
    manifest.append(new Manifest.Entry(Path.of(".claude"), true));
    manifest.append(new Manifest.Entry(Path.of(".claude/a.md"), false));

    List<Manifest.Entry> reloaded = new FileManifest(manifestFile).entries();
    assertEquals(reloaded, List.of(new Manifest.Entry(Path.of(".claude"), true), new Manifest.Entry(Path.of(".claude/a.md"), false)));
  }

  @BeforeMethod
  public void setUp() throws IOException {
    Path base = Files.createDirectories(Path.of("build/test/manifest-" + UUID.randomUUID()));
    manifestFile = base.resolve(Manifest.FILENAME);
  }

  @Test(dataProvider = "unsupportedVersions")
  public void unknownFormatMajorVersionIsRejected(String versionLine) throws IOException {
    Files.writeString(manifestFile, versionLine + "\n.claude/\n");

    Assert.expectThrows(Manifest.UnsupportedManifestException.class, () -> new FileManifest(manifestFile));
    Assert.expectThrows(Manifest.UnsupportedManifestException.class, () -> FileManifest.peek(manifestFile));
  }

  @DataProvider
  public Object[][] unsupportedVersions() {
    return new Object[][]{{"1.0.0"}, {"9.9.9"}, {"1"}, {"not-a-version"}, {"  "}};
  }
}

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.apply.GitExclude;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

public class GitExcludeTest extends BaseTest {

  @Test
  public void addIsIdempotentAndPreservesForeignLines() throws IOException {
    Path location = repository();
    Files.createDirectories(location.resolve(".git/info"));

    Path excludeFile = location.resolve(".git/info/exclude");
    Files.writeString(excludeFile, "# a developer's own line\nscratch.txt\n");
    GitExclude exclude = new GitExclude(location);

    exclude.add(List.of(Path.of(".claude/a.md")));
    exclude.add(List.of(Path.of(".claude/a.md")));

    assertEquals(Files.readAllLines(excludeFile), List.of("# a developer's own line", "scratch.txt", ".claude/a.md"));
  }

  @Test
  public void ensureExcludedIsIdempotentAndPreservesForeignLines() throws IOException {
    Path location = repository();
    Path excludeFile = location.resolve(".git/info/exclude");
    Files.createDirectories(excludeFile.getParent());
    Files.writeString(excludeFile, "# a developer's own line\nscratch.txt\n");
    GitExclude exclude = new GitExclude(location);

    exclude.ensureExcluded(".handler-manifest");
    exclude.ensureExcluded(".handler-manifest");
    exclude.ensureExcluded(".handler-tmp/");

    assertEquals(Files.readAllLines(excludeFile),
                 List.of("# a developer's own line", "scratch.txt", ".handler-manifest", ".handler-tmp/"));
  }

  @Test
  public void nothingEverTouchesGitignore() throws IOException {
    // .gitignore is committed and owned by the team. The Handler's exclusions are per-clone facts about a machine
    // that runs it, and in a clone the developer cannot push to, a modification there would never go away.
    Path location = repository();
    GitExclude exclude = new GitExclude(location);

    exclude.ensureExcluded(".handler-manifest");
    exclude.ensureExcluded(".handler-tmp/");
    exclude.add(List.of(Path.of(".claude/a.md")));
    exclude.remove(List.of(Path.of(".claude/a.md")));

    assertFalse(Files.exists(location.resolve(".gitignore")), "The Handler must never create .gitignore");
  }

  @Test
  public void missingExcludeFileAndParentsAreCreated() throws IOException {
    Path location = repository();
    GitExclude exclude = new GitExclude(location);

    exclude.add(List.of(Path.of(".claude/a.md")));

    assertEquals(Files.readAllLines(location.resolve(".git/info/exclude")), List.of(".claude/a.md"));
  }

  @Test
  public void nonRepositoryIsDetectedAndEveryOperationIsANoOp() throws IOException {
    // This one fixture MUST live outside build/test/. That directory is inside the Handler's own git working tree,
    // so `git rev-parse` would succeed there and resolve to the Handler repository itself — the test would not only
    // fail, it would write the daemon's exclude lines into this project's real .git/info/exclude.
    Path outside = Files.createTempDirectory("handler-non-repo");
    try {
      GitExclude exclude = new GitExclude(outside);

      assertFalse(exclude.repository());
      exclude.add(List.of(Path.of(".claude/a.md")));
      exclude.remove(List.of(Path.of(".claude/a.md")));
      exclude.ensureExcluded(".handler-manifest");

      try (Stream<Path> entries = Files.list(outside)) {
        assertEquals(entries.count(), 0, "Every operation outside a working tree must be a no-op");
      }
    } finally {
      try (Stream<Path> paths = Files.walk(outside)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  @Test
  public void removeIsLineExactAndLeavesEverythingElse() throws IOException {
    Path location = repository();
    GitExclude exclude = new GitExclude(location);
    exclude.add(List.of(Path.of(".claude/a.md"), Path.of(".claude/a.md.bak"), Path.of(".claude/b.md")));
    Files.writeString(location.resolve(".git/info/exclude"), Files.readString(location.resolve(".git/info/exclude")) + "keep-me\n");

    exclude.remove(List.of(Path.of(".claude/a.md")));

    // .claude/a.md.bak has the removed path as a strict prefix; a substring or startsWith match would eat it too
    assertEquals(Files.readAllLines(location.resolve(".git/info/exclude")), List.of(".claude/a.md.bak", ".claude/b.md", "keep-me"));
  }

  @Test
  public void resolutionIsCachedForTheInstanceLifetime() throws IOException {
    Path later = Files.createTempDirectory("handler-late-repo");
    try {
      GitExclude exclude = new GitExclude(later);
      assertFalse(exclude.repository(), "Not a repository yet");

      initRepository(later);

      // The negative result must be cached. Re-resolving would now discover the new repository and start writing,
      // which is exactly the per-cycle subprocess churn the lazy cache exists to prevent.
      assertFalse(exclude.repository(), "Resolution must be cached for the instance's lifetime");
      exclude.add(List.of(Path.of(".claude/a.md")));
      assertFalse(Files.exists(later.resolve(".git/info/exclude")));
    } finally {
      try (Stream<Path> paths = Files.walk(later)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private Path repository() throws IOException {
    Path location = Files.createDirectories(base.resolve("repo"));
    initRepository(location);
    return location;
  }
}

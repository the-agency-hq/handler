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

public class LocationApplierTest extends BaseTest {
  private static final Set<PosixFilePermission> READ_ONLY = PosixFilePermissions.fromString("r--------");

  private Location location;
  private Path root;

  @Test
  public void aFilenameContainingGlobMetacharactersIsHandled() throws IOException {
    // The Brief controls this name and the planner permits brackets. A glob-based stale-temp sweep would throw
    // PatternSyntaxException on the unbalanced one and mis-match siblings on the balanced one.
    assertEquals(apply(plan("notes[draft.md", "content"), false), ApplyResult.APPLIED);
    assertEquals(Files.readString(root.resolve("notes[draft.md")), "content");

    assertEquals(apply(plan("foo[ab].md", "other"), false), ApplyResult.APPLIED);
    assertEquals(Files.readString(root.resolve("foo[ab].md")), "other");
  }

  @Test
  public void aSymlinkedAncestorIsNeverWrittenThrough() throws IOException {
    // Design §8.6's symlink obligation. The planner validates strings and cannot know `.claude -> /somewhere`
    // already exists, so the applier is the only thing standing between a valid planned path and a write outside
    // the Location. This is why every existence check uses NOFOLLOW_LINKS and why ancestors are created one at a
    // time with createDirectory rather than createDirectories, which would happily follow the link.
    Path outside = Files.createDirectories(root.getParent().resolve("outside-" + UUID.randomUUID()));
    Files.createSymbolicLink(root.resolve(".claude"), outside);

    // Unforced: the symlink is an unmanaged entry sitting at a planned path, so the Location is skipped
    assertEquals(apply(plan(".claude/rules/foo.md", "content"), false), ApplyResult.SKIPPED_CONFLICT);
    try (Stream<Path> entries = Files.list(outside)) {
      assertEquals(entries.count(), 0, "A skipped Location must not have written anything");
    }

    // Forced: the link itself is replaced by a real directory; nothing is written through it
    assertEquals(apply(plan(".claude/rules/foo.md", "content"), true), ApplyResult.APPLIED);
    assertFalse(Files.isSymbolicLink(root.resolve(".claude")), "The link must be replaced, not followed");
    assertTrue(Files.isDirectory(root.resolve(".claude"), LinkOption.NOFOLLOW_LINKS));
    assertEquals(Files.readString(root.resolve(".claude/rules/foo.md")), "content");
    try (Stream<Path> entries = Files.list(outside)) {
      assertEquals(entries.count(), 0, "Nothing may be written through the symlink into its target");
    }
  }

  @Test
  public void appliesAPlanAndRecordsEveryCreatedPathInTheManifest() throws IOException {
    ApplyResult result = apply(plan(".claude/rules/foo.md", "Claude rules"), false);

    assertEquals(result, ApplyResult.APPLIED);
    assertEquals(Files.readString(root.resolve(".claude/rules/foo.md")), "Claude rules");
    assertEquals(Files.getPosixFilePermissions(root.resolve(".claude/rules/foo.md")), READ_ONLY);
    assertEquals(Files.getPosixFilePermissions(root.resolve(".claude")), PosixFilePermissions.fromString("rwx------"));
    assertEquals(Files.readAllLines(root.resolve(Manifest.FILENAME)), List.of("0.1.0", ".claude/", ".claude/rules/", ".claude/rules/foo.md"));
  }

  @Test
  public void conflictingUnmanagedFileSkipsTheLocation() throws IOException {
    Files.createDirectories(root.resolve(".claude/rules"));
    Files.writeString(root.resolve(".claude/rules/foo.md"), "mine, not the Handler's");

    ApplyResult result = apply(plan(".claude/rules/foo.md", "theirs"), false);

    assertEquals(result, ApplyResult.SKIPPED_CONFLICT);
    assertEquals(Files.readString(root.resolve(".claude/rules/foo.md")), "mine, not the Handler's");
  }

  @Test
  public void emptyPlanTearsTheLocationDownCompletely() throws IOException {
    apply(plan(".claude/rules/foo.md", "content"), false);

    ApplyResult result = apply(LocationPlan.EMPTY, false);

    assertEquals(result, ApplyResult.APPLIED);
    assertFalse(Files.exists(root.resolve(".claude")), "Every created directory must be removed");
    assertEquals(Files.readAllLines(root.resolve(Manifest.FILENAME)), List.of("0.1.0"));
  }

  @Test
  public void forceAdoptsAConflictAndTheHandlerWins() throws IOException {
    Files.createDirectories(root.resolve(".claude/rules"));
    Files.writeString(root.resolve(".claude/rules/foo.md"), "mine");

    ApplyResult result = apply(plan(".claude/rules/foo.md", "theirs"), true);

    assertEquals(result, ApplyResult.APPLIED);
    assertEquals(Files.readString(root.resolve(".claude/rules/foo.md")), "theirs");
    // Only the file is recorded. The manifest lists what the Handler CREATED (§8.4), and both directories already
    // existed — adopting them would make them teardown candidates and the Handler would eventually delete
    // directories a developer made. §3.1 item 2 is explicit that the plan names ancestors it did not create.
    assertEquals(Files.readAllLines(root.resolve(Manifest.FILENAME)), List.of("0.1.0", ".claude/rules/foo.md"));
  }

  @Test
  public void forceAdoptsAManagedFilePathThatBecameADirectory() throws IOException {
    apply(plan(".claude/rules/foo.md", "content"), false);

    // The developer deletes the managed file and makes a directory of the same name, with something in it
    Files.delete(root.resolve(".claude/rules/foo.md"));
    Files.createDirectories(root.resolve(".claude/rules/foo.md/nested"));
    Files.writeString(root.resolve(".claude/rules/foo.md/nested/theirs.txt"), "mine");

    // Unforced this is a conflict and the Location is skipped
    assertEquals(apply(plan(".claude/rules/foo.md", "content"), false), ApplyResult.SKIPPED_CONFLICT);

    // Forced, teardown must SKIP the directory rather than throwing DirectoryNotEmptyException, so the write
    // step's adopt path can replace it. Before the fix this returned FAILED every cycle, forever.
    assertEquals(apply(plan(".claude/rules/foo.md", "content"), true), ApplyResult.APPLIED);
    assertEquals(Files.readString(root.resolve(".claude/rules/foo.md")), "content");
    assertTrue(Files.isRegularFile(root.resolve(".claude/rules/foo.md"), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  public void gitExcludeIsKeptInSyncWithTheManifest() throws IOException {
    apply(plan(".claude/rules/foo.md", "content"), false);

    assertEquals(Files.readAllLines(root.resolve(".git/info/exclude")),
                 List.of(Manifest.FILENAME, ".claude/rules/foo.md", Manifest.STAGING_DIRECTORY + "/"));
    assertFalse(Files.exists(root.resolve(".gitignore")), "The Handler never writes the team's .gitignore");

    ApplyResult result = apply(LocationPlan.EMPTY, false);

    assertEquals(result, ApplyResult.APPLIED);
    // The Brief's file line is gone. The Handler's own two names stay: the manifest still exists after a teardown,
    // and the staging directory is re-created by the next write that has anything to do.
    assertEquals(Files.readAllLines(root.resolve(".git/info/exclude")),
                 List.of(Manifest.FILENAME, Manifest.STAGING_DIRECTORY + "/"));
  }

  @Test
  public void manifestEntryWithNoFileIsRecoveredOnTheNextApply() throws IOException {
    // Simulates a crash between the flushed manifest append and the file write
    Files.writeString(root.resolve(Manifest.FILENAME), "0.1.0\n.claude/\n.claude/rules/\n.claude/rules/foo.md\n");

    ApplyResult result = apply(plan(".claude/rules/foo.md", "content"), false);

    assertEquals(result, ApplyResult.APPLIED);
    assertEquals(Files.readString(root.resolve(".claude/rules/foo.md")), "content");
    assertEquals(Files.readAllLines(root.resolve(Manifest.FILENAME)), List.of("0.1.0", ".claude/", ".claude/rules/", ".claude/rules/foo.md"));
  }

  @Test
  public void multiFileNestedPlanRecordsParentsBeforeChildrenAndTeardownRemovesEverything() throws IOException {
    LocationPlan locationPlan = plan(List.of(".claude/skills/one/SKILL.md", ".claude/skills/one/scripts/run.sh", ".claude/rules/a.md", "top.md"));

    ApplyResult result = apply(locationPlan, false);

    assertEquals(result, ApplyResult.APPLIED);
    assertEquals(Files.readString(root.resolve(".claude/skills/one/SKILL.md")), ".claude/skills/one/SKILL.md");
    assertEquals(Files.readString(root.resolve(".claude/skills/one/scripts/run.sh")), ".claude/skills/one/scripts/run.sh");
    assertEquals(Files.readString(root.resolve(".claude/rules/a.md")), ".claude/rules/a.md");
    assertEquals(Files.readString(root.resolve("top.md")), "top.md");

    List<Path> directories = FileManifest.peek(root.resolve(Manifest.FILENAME))
                                         .stream()
                                         .filter(Manifest.Entry::directory)
                                         .map(Manifest.Entry::path)
                                         .toList();
    assertEquals(new HashSet<>(directories),
        Set.of(
            Path.of(".claude"),
            Path.of(".claude/skills"),
            Path.of(".claude/skills/one"),
            Path.of(".claude/skills/one/scripts"),
            Path.of(".claude/rules")
        )
    );

    for (Path ancestor : directories) {
      for (Path descendant : directories) {
        if (!ancestor.equals(descendant) && descendant.startsWith(ancestor)) {
          assertTrue(directories.indexOf(ancestor) < directories.indexOf(descendant), "[" + ancestor + "] must be recorded before its descendant [" + descendant + "]");
        }
      }
    }

    assertEquals(apply(LocationPlan.EMPTY, false), ApplyResult.APPLIED);
    assertFalse(Files.exists(root.resolve(".claude")), "The whole managed tree must be removed");
    assertFalse(Files.exists(root.resolve("top.md")));
  }

  @Test
  public void nonEmptyDirectoryIsLeftAloneDuringTeardown() throws IOException {
    apply(plan(".claude/rules/foo.md", "content"), false);
    Files.writeString(root.resolve(".claude/rules/mine.md"), "the developer put this here");

    ApplyResult result = apply(LocationPlan.EMPTY, false);

    assertEquals(result, ApplyResult.APPLIED);
    assertTrue(Files.exists(root.resolve(".claude/rules/mine.md")), "Unmanaged content is never destroyed");
    assertFalse(Files.exists(root.resolve(".claude/rules/foo.md")));
  }

  @Test
  public void preExistingDirectoriesSurviveTeardown() throws IOException {
    Files.createDirectories(root.resolve(".claude/rules"));
    apply(plan(".claude/rules/foo.md", "content"), true);

    assertEquals(apply(LocationPlan.EMPTY, false), ApplyResult.APPLIED);

    assertFalse(Files.exists(root.resolve(".claude/rules/foo.md")), "The managed file goes");
    assertTrue(Files.isDirectory(root.resolve(".claude/rules")), "The developer's directories stay");
    assertTrue(Files.isDirectory(root.resolve(".claude")));
  }

  @Test
  public void readOnlyManagedFileIsStillReplaced() throws IOException {
    apply(plan(".claude/rules/foo.md", "first"), false);
    assertEquals(Files.getPosixFilePermissions(root.resolve(".claude/rules/foo.md")), READ_ONLY);

    ApplyResult result = apply(plan(".claude/rules/foo.md", "second"), false);

    assertEquals(result, ApplyResult.APPLIED);
    assertEquals(Files.readString(root.resolve(".claude/rules/foo.md")), "second");
  }

  @BeforeMethod
  public void setUp() throws IOException {
    root = Files.createDirectories(Path.of("build/test/apply-" + UUID.randomUUID()).toAbsolutePath());
    // build/test/ lives inside the Handler's own git working tree, so without its own repository GitExclude would
    // resolve straight to this project's real .git/info/exclude - exactly the trap GitExcludeTest documents
    initRepository(root);
    location = new Location(root, "42", List.of(), List.of());
  }

  @Test
  public void staleStagingDirectoryIsSweptEvenWhenNothingChanges() throws IOException {
    // A crash leaves orphans in .handler-tmp/. They must be collected even on a cycle that writes nothing, and
    // regardless of which files the current Brief still names - the old per-file sweep could do neither.
    apply(plan(".claude/rules/foo.md", "content"), false);
    Path staging = Files.createDirectories(root.resolve(Manifest.STAGING_DIRECTORY));
    Files.writeString(staging.resolve("orphan-from-a-prior-crash"), "half a file");

    assertEquals(apply(plan(".claude/rules/foo.md", "content"), false), ApplyResult.UNCHANGED);
    assertFalse(Files.exists(staging), "The staging directory is swept on every apply, not only when writing");
  }

  @Test
  public void stagingDirectoryIsRemovedAfterAWriteAndNeverRecordedInTheManifest() throws IOException {
    ApplyResult result = apply(plan(".claude/rules/foo.md", "content"), false);

    assertEquals(result, ApplyResult.APPLIED);
    assertFalse(Files.exists(root.resolve(Manifest.STAGING_DIRECTORY)),
                "Its presence must keep meaning that a write died partway through");
    assertFalse(Files.readAllLines(root.resolve(Manifest.FILENAME)).contains(Manifest.STAGING_DIRECTORY + "/"),
                "Transient scaffolding is not delivered content, so teardown must never see it");
  }

  @Test
  public void anOrphanedStagedFileDoesNotBlockTeardownOfAContentDirectory() throws IOException {
    // The bug the staging directory exists to fix. Staged beside its target, an orphan from a crashed write sits
    // inside .claude/rules/, which makes the directory non-empty, so teardown refuses to remove it - forever, since
    // the old per-file sweep only ran for files the current Brief still named. At the Location root it is inert.
    apply(plan(".claude/rules/foo.md", "content"), false);
    Path staging = Files.createDirectories(root.resolve(Manifest.STAGING_DIRECTORY));
    Files.writeString(staging.resolve("orphan-from-a-prior-crash"), "half a file");

    assertEquals(apply(LocationPlan.EMPTY, false), ApplyResult.APPLIED);

    assertFalse(Files.exists(root.resolve(".claude")), "Teardown must remove everything the Handler created");
    assertFalse(Files.exists(staging), "And the orphan with it");
  }

  @Test
  public void unchangedInputWritesNothingAtAll() throws IOException {
    apply(plan(".claude/rules/foo.md", "content"), false);
    Path file = root.resolve(".claude/rules/foo.md");
    Path manifest = root.resolve(Manifest.FILENAME);
    FileTime fileTime = Files.getLastModifiedTime(file);
    FileTime manifestTime = Files.getLastModifiedTime(manifest);

    ApplyResult result = apply(plan(".claude/rules/foo.md", "content"), false);

    assertEquals(result, ApplyResult.UNCHANGED);
    assertEquals(Files.getLastModifiedTime(file), fileTime);
    assertEquals(Files.getLastModifiedTime(manifest), manifestTime, "The manifest must not be rewritten");
  }

  @Test
  public void unknownManifestFormatIsTreatedAsAConflict() throws IOException {
    Files.writeString(root.resolve(Manifest.FILENAME), "9.0.0\n.claude/\n");

    assertEquals(apply(plan(".claude/rules/foo.md", "content"), false), ApplyResult.SKIPPED_CONFLICT);
  }

  @Test
  public void versionBumpRemovesFilesTheNewPlanDropped() throws IOException {
    apply(plan(".claude/rules/old.md", "old"), false);

    ApplyResult result = apply(plan(".claude/rules/new.md", "new"), false);

    assertEquals(result, ApplyResult.APPLIED);
    assertEquals(Files.readString(root.resolve(".claude/rules/new.md")), "new");
    assertFalse(Files.exists(root.resolve(".claude/rules/old.md")));
  }

  private ApplyResult apply(LocationPlan plan, boolean force) {
    return new LocationApplier().apply(location, plan, force);
  }

  private LocationPlan plan(List<String> relativePaths) {
    List<PlannedFile> files = new ArrayList<>();
    SequencedSet<Path> directories = new LinkedHashSet<>();
    for (String relativePath : relativePaths) {
      Path path = Path.of(relativePath);
      List<Path> ancestors = new ArrayList<>();
      for (Path ancestor = path.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
        ancestors.add(ancestor);
      }

      directories.addAll(ancestors.reversed());
      files.add(new PlannedFile(path, relativePath.getBytes(StandardCharsets.UTF_8), READ_ONLY));
    }

    return new LocationPlan(files, directories);
  }

  private LocationPlan plan(String relativePath, String content) {
    Path path = Path.of(relativePath);
    List<Path> ancestors = new ArrayList<>();
    for (Path ancestor = path.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
      ancestors.add(ancestor);
    }

    SequencedSet<Path> directories = new LinkedHashSet<>(ancestors.reversed());
    PlannedFile file = new PlannedFile(path, content.getBytes(StandardCharsets.UTF_8), READ_ONLY);

    return new LocationPlan(List.of(file), directories);
  }
}

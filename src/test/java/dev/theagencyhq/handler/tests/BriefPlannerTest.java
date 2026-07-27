/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationPlan;
import dev.theagencyhq.handler.apply.PlannedFile;
import dev.theagencyhq.handler.brief.Brief;
import dev.theagencyhq.handler.brief.StoredBrief;
import dev.theagencyhq.handler.location.Location;

public class BriefPlannerTest extends BaseTest {
  @Test
  public void ancestorDirectoriesAreRecordedShallowestFirst() {
    LocationPlan plan = plan("[{\"path\":\".claude/skills/one/SKILL.md\",\"content\":\"x\"}]", List.of());

    Assert.assertEquals(List.copyOf(plan.directories()),
                        List.of(Path.of(".claude"), Path.of(".claude/skills"), Path.of(".claude/skills/one")));
  }

  @Test
  public void anEmptyPlansDirectoriesCannotBeMutated() {
    // EMPTY is a shared constant - a caller mutating it would poison it for the whole JVM
    Assert.expectThrows(UnsupportedOperationException.class, () -> LocationPlan.EMPTY.directories().add(Path.of("x")));
  }

  @Test
  public void base64FilesDecodeAndModesParse() {
    LocationPlan plan = plan("[{\"path\":\"bin/tool\",\"encoding\":\"base64\",\"mode\":\"rwxr-xr-x\",\"content\":\"AAEC\"}]",
                             List.of());

    PlannedFile file = plan.files().getFirst();
    Assert.assertEquals(file.content(), new byte[]{0, 1, 2});
    Assert.assertEquals(file.mode(), PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  @Test
  public void filesAreSortedByPath() {
    LocationPlan plan = plan("""
        [{"path":"z.md","content":"z"},{"path":"a.md","content":"a"},{"path":".claude/m.md","content":"m"}]""",
                             List.of());

    Assert.assertEquals(plan.files().stream().map(PlannedFile::relativePath).toList(),
                        List.of(Path.of(".claude/m.md"), Path.of("a.md"), Path.of("z.md")));
  }

  @Test(dataProvider = "invalidPaths")
  public void invalidPathsFailTheEntirePlan(String path) {
    // Note the second file is perfectly valid - one bad path must take the whole plan down, not just its own entry
    String files = """
        [{"path":"%s","content":"evil"},{"path":".claude/fine.md","content":"fine"}]""".formatted(path);

    Assert.expectThrows(BriefPlanner.InvalidPlanException.class, () -> plan(files, List.of()));
  }

  @DataProvider
  public Object[][] invalidPaths() {
    return new Object[][]{
        {"/etc/passwd"},                    // absolute
        {"../escape.md"},                   // parent traversal
        {".claude/../../escape.md"},        // traversal that normalizes outside the root
        {"./relative.md"},                  // a "." segment
        {".claude/./a.md"},
        {""},                               // empty
        {".git/config"},                    // the first segment is .git
        {".git"},
        {".gitignore"},                     // committed and team-owned; the Handler wins at managed paths, with no merge
        {"sub/.GITIGNORE"},                 // case-insensitively, and at any depth
        {".handler-manifest"},              // the Handler's own bookkeeping

        // Everything below was ACCEPTED by the original first-segment-only, case-sensitive validator. An adversarial
        // review compiled the planner and executed working exploits for the first three.
        {"tools/.git/config"},                 // fabricated repo; core.fsmonitor runs on the next git call
        {"vendor/lib/.git/hooks/pre-commit"},  // overwrites a real hook in a nested clone
        {".GIT/hooks/pre-commit"},             // macOS APFS is case-insensitive: this IS .git/hooks/pre-commit
        {"sub/.GIT/config"},
        {".HANDLER-MANIFEST"},                 // aliases the real manifest on a case-insensitive filesystem
        {"sub/.handler-manifest"},             // forged manifest, activates if sub ever becomes a Location
        {".handler-tmp/x.md"},                 // the applier deletes this directory around every apply
        {"sub/.HANDLER-TMP/y.md"},             // case-insensitively, and at any depth

        // The next three are JSON escape sequences, NOT literal control characters. These values are interpolated
        // into a JSON document, and a raw control character inside a JSON string is invalid JSON (RFC 8259), so the
        // parser would reject them before BriefPlanner ever ran. That incidental rejection is real defence in depth,
        // but it is not the guarantee under test — escaped, the parser decodes them to genuine control characters
        // which then reach validate(), the layer that must reject them.
        {"evil\\n/Users/dev/.ssh/authorized_keys"}, // newline injects an absolute line into the manifest
        {"a\\u0000b.md"},                       // NUL must fail as InvalidPlanException, not InvalidPathException
        {"tab\\there.md"}
    };
  }

  @Test
  public void missionTypeFilteringCanProduceAnEmptyPlan() {
    LocationPlan plan = plan("""
        [{"path":".claude/web.md","content":"w","missionTypes":["web"]}]""", List.of("library"));

    Assert.assertTrue(plan.isEmpty());
    Assert.assertEquals(plan.files(), List.of());
    Assert.assertEquals(plan.directories(), Set.of());
  }

  @Test
  public void missionTypesSelectWhichFilesAreIncluded() {
    String files = """
        [{"path":"a.md","content":"a","missionTypes":["web"]},
         {"path":"b.md","content":"b","missionTypes":["library"]},
         {"path":"c.md","content":"c"}]""";

    LocationPlan plan = plan(files, List.of("web"));

    Assert.assertEquals(plan.files().stream().map(PlannedFile::relativePath).toList(),
                        List.of(Path.of("a.md"), Path.of("c.md")));
  }

  @Test
  public void unrepresentableModeFailsTheEntirePlan() {
    // setuid: valid ls -l notation, but PosixFilePermission has no constant for it
    Assert.expectThrows(BriefPlanner.InvalidPlanException.class,
                        () -> plan("[{\"path\":\"a.md\",\"mode\":\"rwsr-xr-x\",\"content\":\"a\"}]", List.of()));
  }

  @Test
  public void theSamePathPlannedTwiceFailsTheEntirePlan() {
    // `a//b.md` and `a/b.md` normalize identically; writing both would duplicate the manifest entry
    Assert.expectThrows(BriefPlanner.InvalidPlanException.class,
                        () -> plan("[{\"path\":\"a//b.md\",\"content\":\"1\"},{\"path\":\"a/b.md\",\"content\":\"2\"}]",
                                   List.of()));
  }

  private LocationPlan plan(String filesJSON, List<String> locationMissionTypes) {
    String json = """
        {"checksum":"c","organization":{"id":"42","name":"Org"},"version":1,"files":%s}""".formatted(filesJSON);
    Brief brief = Brief.fromJSON(json.getBytes(StandardCharsets.UTF_8));
    StoredBrief stored = new StoredBrief(brief, Path.of("build/test/unused/brief.json"));
    Location location = new Location(Path.of("build/test/unused-location"), "42", locationMissionTypes);

    return new BriefPlanner().plan(stored, location);
  }
}

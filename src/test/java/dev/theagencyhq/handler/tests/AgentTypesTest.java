/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import java.util.Optional;
import org.lattejava.version.Version;

import static org.testng.Assert.*;

// Disambiguates java.util.Optional and org.lattejava.version.Version from their TestNG namesakes, both pulled in by the
// module imports

public class AgentTypesTest extends BaseTest {
  @Test
  public void agentTypeIsTheLeadingDotDirectory() {
    assertEquals(AgentTypes.agentType(Path.of(".claude/rules/a.md")), Optional.of("claude"));
    assertEquals(AgentTypes.agentType(Path.of(".agents/skills/s/SKILL.md")), Optional.of("agents"));
    assertEquals(AgentTypes.agentType(Path.of(".Codex/config.toml")), Optional.of("codex"));
  }

  @Test
  public void aliasesAndDirectoryNamesBothMatchAndTheEnteredNameIsKept() {
    LocationMarker marker = new LocationMarker(new Version("1.0.0"), "42", null, List.of("Copilot", "kimi"));

    // The marker keeps what the developer typed; only matching resolves the alias
    assertEquals(marker.agentTypes(), List.of("copilot", "kimi"));
    assertEquals(AgentTypes.directory("copilot"), "github");
    assertEquals(AgentTypes.directory("github"), "github");
    assertEquals(AgentTypes.directory("claude"), "claude");
    assertTrue(AgentTypes.includes(Path.of(".github/copilot-instructions.md"), marker.agentTypes()));
    assertTrue(AgentTypes.includes(Path.of(".kimi-code/rules/a.md"), marker.agentTypes()));
    assertFalse(AgentTypes.includes(Path.of(".claude/rules/a.md"), marker.agentTypes()));
  }

  @Test
  public void filesOutsideADotDirectoryBelongToNoAgent() {
    assertEquals(AgentTypes.agentType(Path.of("README.md")), Optional.empty());
    assertEquals(AgentTypes.agentType(Path.of("docs/guide.md")), Optional.empty());
    // A dot-file at the root is a file, not an Agent directory
    assertEquals(AgentTypes.agentType(Path.of(".editorconfig")), Optional.empty());
  }

  @Test
  public void markerNormalizationDropsDotsCaseAndBlanks() {
    LocationMarker marker = new LocationMarker(new Version("1.0.0"), "42", null,
                                               List.of(" Claude ", ".codex", "..AGENTS", " ", ""));

    assertEquals(marker.agentTypes(), List.of("claude", "codex", "agents"));
    assertTrue(AgentTypes.includes(Path.of(".claude/a.md"), marker.agentTypes()));
  }

  @Test(dataProvider = "truthTable")
  public void truthTable(String path, List<String> locationTypes, boolean expected) {
    assertEquals(AgentTypes.includes(Path.of(path), locationTypes), expected);
  }

  @DataProvider
  public Object[][] truthTable() {
    // @formatter:off
    return new Object[][]{
        //  Brief file path                  Location agent type(s)             Include?
        {".claude/rules/a.md",               List.of(),                         true},
        {".claude/rules/a.md",               List.of("claude"),                 true},
        {".claude/rules/a.md",               List.of("codex"),                  false},
        {".claude/rules/a.md",               List.of("codex", "claude"),        true},
        {".agents/skills/s/SKILL.md",        List.of("claude", "agents"),       true},
        {".agents/AGENTS.md",                List.of("junie"),                  false},
        {".agents/AGENTS.md",                List.of("antigravity"),            true},
        {".github/copilot-instructions.md",  List.of("copilot"),                true},
        {".github/copilot-instructions.md",  List.of("github"),                 true},
        {".github/copilot-instructions.md",  List.of("claude"),                 false},
        {".clinerules/a.md",                 List.of("cline"),                  true},
        {".clinerules/a.md",                 List.of("clinerules"),             true},
        {".kimi-code/a.md",                  List.of("kimi"),                   true},
        {"README.md",                        List.of("claude"),                 true},
        {"docs/guide.md",                    List.of("claude"),                 true},
        {".editorconfig",                    List.of("claude"),                 true}
    };
    // @formatter:on
  }
}

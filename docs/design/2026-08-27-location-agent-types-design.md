# Location agent types

Status: proposed

Amends §8 of `2026-07-26-handler-core-sync-design.md`. A Location can now say which Agents it wants files for. A
Location that lists none gets every file, exactly as before.

## 1. The marker field

`agent-location.json` gains an optional `agentTypes` list:

```json
{
  "version": "1.0.0",
  "organizationId": "42",
  "missionTypes": ["java"],
  "agentTypes": ["claude", "codex", "agents", "junie"]
}
```

| Value     | Meaning                                                                                         |
|-----------|-------------------------------------------------------------------------------------------------|
| absent/[] | Every Brief file is included                                                                    |
| `claude`  | Files under `.claude/` are included                                                             |
| `agents`  | Files under `.agents/` are included — the standard layout The Agency's `StandardTranslator` writes |
| any `x`   | Files under `.x/` are included                                                                  |

An agent type names the dot-directory at the Location root that the Agent reads from. The Agency writes every
Agent's files under one dot-directory, so for most Agents the name is the directory. A few Agents read from a
directory that is not their own name; for those the Handler keeps a small alias table, and either name works:

| Agent name    | Directory name | Directory       |
|---------------|----------------|-----------------|
| `copilot`     | `github`       | `.github/`      |
| `cline`       | `clinerules`   | `.clinerules/`  |
| `kimi`        | `kimi-code`    | `.kimi-code/`   |
| `antigravity` | `agents`       | `.agents/`      |

Values are trimmed, lowercased, and a leading `.` is dropped, so `.Claude` and `claude` mean the same thing. The
marker keeps the name as entered (`copilot` stays `copilot` in `status` output); aliases are resolved only when a
file is matched. The marker format version stays `1.0.0` because an older Handler ignores the field and includes
everything.

## 2. Filtering

`BriefPlanner` decides per file, after path validation and alongside the Mission Type check:

| Brief file path            | Location `agentTypes`  | Included? |
|----------------------------|------------------------|-----------|
| `.claude/rules/a.md`       | `[]`                   | yes       |
| `.claude/rules/a.md`       | `["claude"]`           | yes       |
| `.claude/rules/a.md`       | `["codex"]`            | no        |
| `.agents/skills/s/SKILL.md`| `["claude", "agents"]` | yes       |
| `README.md`                | `["claude"]`           | yes       |

A file's agent type is its first path segment without the leading dot, and only when that segment is a directory
that starts with a dot. A file at the root, or under a directory with no leading dot, belongs to no Agent and is
always included — the same rule Mission Types use for a file with no types.

Both filters must pass. A file excluded by either is simply not planned; nothing is validated differently and an
excluded file still counts for nothing in the manifest. When filtering excludes everything the plan is empty,
which tears the Location down like any other empty plan.

## 3. Code changes

| Change                                                                       | Where            |
|------------------------------------------------------------------------------|------------------|
| New `AgentTypes`: normalizes values, alias table, derives a file's agent type, `includes` | `location` |
| `agentTypes` component, normalized in the compact constructor               | `LocationMarker` |
| `agentTypes` component                                                       | `Location`       |
| `agentTypes` in `state.json`; absent means empty                            | `LocationEntry`  |
| Agent type check after path validation                                       | `BriefPlanner`   |
| Asks for agent types after Mission Types                                     | `Init`           |
| Prints an `Agent types:` line under `Mission types:`                         | `Status`         |

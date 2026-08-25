# Brief Source

This repository is a Brief Source for The Agency. Every push to the connected branch is built into a Brief and
delivered by the Handler to every Location in your Organization.

## Layout

| Directory   | Published to                               |
|-------------|--------------------------------------------|
| `rules/`    | `.claude/rules/` and `.codex/rules/`       |
| `skills/`   | `.claude/skills/` and `.codex/skills/`     |
| `agents/`   | `.claude/agents/` and `.codex/agents/`     |
| `commands/` | `.claude/commands/` and `.codex/commands/` |
| `claude/`   | `.claude/` (Claude Code only)              |
| `codex/`    | `.codex/` (Codex only)                     |

- Every file under a published directory ships, including the `.gitkeep` placeholders. Delete them once you add
  real files.
- Files outside these directories (this README, for example) are ignored.
- Symbolic links are not allowed anywhere in the repository.
- `the-agency-hq-settings.json` marks this repository as a Brief Source. Leave `version` at `1.0.0`.

## Mission Types

A Location declares the Mission Types it accepts when it is set up with `handler init`. A file with no Mission Types
goes to every Location. To limit a file or a directory, add a `.mission-types` file with one Mission Type per line.
Matching is case-insensitive.

| File                                    | Applies to                            |
|-----------------------------------------|---------------------------------------|
| `skills/deploy/SKILL.md.mission-types`  | Only `skills/deploy/SKILL.md`         |
| `skills/deploy/.mission-types`          | Every file under `skills/deploy/`     |
| `.mission-types` (repository root)      | Every file in the repository          |

The nearest declaration wins: a file's own `<name>.mission-types` beats its directory's `.mission-types`, which beats
any parent directory's. `.mission-types` files are never published.

Example `skills/deploy/.mission-types`:

```
web
library
```

## Publishing

1. Commit and push this repository to GitHub.
2. In The Agency, open your Organization, connect GitHub, and pick this repository and branch.
3. The Agency polls the branch and publishes a new Brief whenever the content changes.

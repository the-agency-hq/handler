# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

The Agency's Handler daemon

## Documentation

- `docs/design/` — all design documents and specs (filenames prefixed with `YYYY-MM-DD-` creation date)
- `docs/implementation/` — all implementation plans (filenames prefixed with `YYYY-MM-DD-` creation date)

## Worktree

Worktrees should be created in the `.worktrees` directory in the root of the project.

## Build & run

This project is built with `latte` (the Latte build tool, project file is `project.latte`), not Maven/Gradle. Java 25 is required (set in `project.latte`).

| Task                      | Command                                                       |
|---------------------------|---------------------------------------------------------------|
| Compile + jar             | `latte build`                                                 |
| Run tests                 | `latte test` (depends on `build`)                             |
| Run a single test         | `latte test --test=dev.theagencyhq.daemon.tests.UpdaterTest`  |
| Local integration release | `latte int` (publishes to local integration repo)             |
| Refresh IntelliJ module   | `latte idea`                                                  |
| Clean                     | `latte clean`                                                 |

### Module system

Both `src/main` and `src/test` are JPMS modules (`module-info.java`). When adding new external dependencies, update `project.latte` **and** the appropriate `module-info.java` `requires` clause. Internal packages used by tests must be `exports`ed (or `opens` to TestNG, as `dev.theagencyhq.daemon.tests` already does).

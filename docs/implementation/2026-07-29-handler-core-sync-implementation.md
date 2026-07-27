# Handler Core Sync Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Handler daemon's core sync engine — receive Briefs from The Agency into an immutable local store, and distribute them into every Location on the machine via a manifest-driven apply.

**Architecture:** Two independent service threads (`ReceiveThread`, `DistributeThread`, both extending `IntervalThread`) coupled only by the Brief store on disk plus a one-way, best-effort nudge. Receive fetches Briefs and writes their exact wire bytes atomically. Distribute scans for `agent-location.json` markers, plans an in-memory file set per Location, and applies it through a flushed-per-append `.handler-manifest` that makes every partial write recoverable. Packages are strictly layered: each depends only on those above it in the map below.

**Tech Stack:** Java 25 (JPMS, records, sealed interfaces, pattern matching), `latte` build tool, `org.lattejava:json` 0.4.3 annotation processor (compile-time only), `org.lattejava:http` (test-scoped, for the fake Agency), TestNG 7.10.2.

**Source spec:** `docs/design/2026-07-26-handler-core-sync-design.md`. Section references below (§7.4, §8.6, …) point into it. Read the referenced section before starting a task — this plan gives you the code, the spec gives you the reasoning.

## Global Constraints

Every task's requirements implicitly include this section.

- **Java 25.** Set in `project.latte` (`java.settings.javaVersion = "25"`, `javaTestNG.settings.javaVersion = "25"`) and pinned for the toolchain by `.javaversion` at the repository root. Both are already correct; do not change either.
- **Package root is `dev.theagencyhq.handler`.** The POC's `dev.theagencyhq.daemon` is renamed in Task 1 and must not reappear.
- **Copyright header on every `.java` file including `module-info.java`**, as the very first thing in the file, no blank line above it, exactly:
  ```java
  /*
   * Copyright (c) 2026 The Agency HQ
   * SPDX-License-Identifier: MIT
   */
  ```
- **No new dependencies.** The JDK, plus `org.lattejava:json` 0.4.1 at compile time, plus test-scoped `org.lattejava:http` and `org.testng:testng`. Nothing else, ever.
- **No reflection.**
- **`System.Logger` only** for logging. Never `java.util.logging` directly outside `Logging`, never `System.out`/`System.err` outside the CLI's own user-facing output.
- **Runtime values in all error and log messages use `[value]` brackets** — `"Malformed marker [" + path + "]"`, never quotes.
- **2-space indent, 4-space continuation indent, 120-column target.** Do not wrap before 120 columns.
- **Uppercase acronyms in identifiers** — `theAgencyURL`, `toJSON()`, `HTTPClient`, `AgencyClient`. Never `Url`, `Json`, `Http`.
- **Alphabetize** fields, methods (within visibility group), imports, and `requires`/`exports`/`opens` clauses. No blank lines between field declarations.
- **Prefer module imports** — `import module java.base;` over individual class imports.
- **Test classes that use `java.nio.file.Files` must add an explicit `import java.nio.file.Files;`.** `import module java.base;` and `import module org.testng;` both export a `Files` type (`java.nio.file.Files` and `org.testng.reporters.Files`), so the bare name is ambiguous and the file will not compile. A single-type import outranks both on-demand imports and resolves it. The POC's `UpdaterTest` already did exactly this. Every test listing in this plan that calls `Files.` needs the line, whether or not it is shown.
- **Models normalize in their compact constructors.** Never in a factory or a caller.
- **Always `toLowerCase(Locale.ROOT)`, never bare `toLowerCase()`.** The no-arg form uses the default locale, and under a Turkish, Azeri, or Lithuanian locale `"Library".toLowerCase()` yields `"lıbrary"` with a dotless ı. Mission Type matching would then silently fail on that developer's machine — no error, just files quietly not distributed. This binds every normalization in `BriefFile` and `LocationMarker`.
- **POSIX file permissions are used directly and unconditionally.** macOS and Linux only. No Windows branch, no `PosixFileAttributeView` availability check.
- **Tests never touch the real home directory.** Every test injects a `HandlerPaths` rooted under `build/test/`.
- **Conventional Commits** on the current feature branch (`feat/handler-core-sync`). Never commit to `main`. One commit per task, as the task's final step.
- **JPMS:** when a task adds a package, that task also adds its `exports` line to `src/main/java/module-info.java`. Tests read internal packages, so every package is exported.

## Checking artifact versions

**Never infer whether an artifact exists from the contents of `~/.cache/latte`** — that directory holds only what this machine has already fetched, so an absence there means nothing. The authoritative source is Latte's repository search API:

```bash
curl -sS 'https://api.lattejava.org/api/v1/repository/search?id=org.lattejava:json'
# {"id":"org.lattejava:json","versions":["0.4.1","0.4.0","0.3.0","0.2.0","0.1.0"]}

curl -sS 'https://api.lattejava.org/api/v1/repository/search?id=org.lattejava:json&latest=true'
# {"id":"org.lattejava:json","versions":["0.4.1"]}
```

Verified 2026-07-29 by that API: **`org.lattejava:json:0.4.1` is published**, and it is the latest. `@JSONRaw` (spec §14) ships in it. Task 1 bumps `project.latte` from `0.4.0` to `0.4.1` and that is the whole of it — nothing needs building or publishing anywhere else.

`latte install <artifact-id> [version] [group]` resolves the latest version when the version is omitted, and `latte upgrade` does the same for existing entries.

## File Structure

One JPMS module, `dev.theagencyhq.handler`. Each package depends only on the packages above it in this list; nothing depends on a package below it. Violating that direction is a design break, not a style nit.

| Path | Responsibility |
|------|----------------|
| `config/HandlerPaths.java` | XDG path resolution. The only place environment variables are read. |
| `config/HandlerConfig.java` | `@JSON` record of `handler.json`, normalizing in its compact constructor. |
| `config/ConfigLoader.java` | Reads/creates the config file, applies the env override. |
| `log/Logging.java` | Configures the JUL backend behind `System.Logger`. Called once, from `Main`. |
| `brief/Organization.java` | `@JSON` record — `id`, `name`. |
| `brief/BriefFile.java` | `@JSON` record of one file in a Brief, with decode and mode parsing. |
| `brief/Brief.java` | `@JSON` record with the `@JSONRaw` wire-bytes capture. |
| `brief/StoredBrief.java` | A Brief plus the store path it was read from. |
| `brief/BriefStore.java` | Interface + `FileBriefStore` — atomic writes, `latest`, revoke, purge. |
| `agency/TokenSupplier.java` | Bearer token indirection so OAuth drops in later. |
| `agency/BriefingRequest.java` | `@JSON` request body — `currentVersions`. |
| `agency/BriefingResponse.java` | `@JSON` 200 body — `organizationIds`, `briefs`. |
| `agency/BriefingResult.java` | Sealed result: `Updated`/`NotModified`/`Forbidden`/`Failed`. |
| `agency/AgencyClient.java` | `HttpClient` wrapper mapping status codes onto `BriefingResult`. |
| `location/LocationMarker.java` | `@JSON` record of `agent-location.json`. |
| `location/Location.java` | A discovered Location — root path plus marker fields. |
| `location/MissionTypes.java` | The one-line inclusion predicate. |
| `location/LocationScanner.java` | Depth-first marker discovery with pruning and exclusion globs. |
| `apply/PlannedFile.java` | One file to write — relative path, decoded bytes, POSIX mode. |
| `apply/LocationPlan.java` | The full in-memory plan for one Location. |
| `apply/BriefPlanner.java` | Brief + Location → `LocationPlan`. Path validation lives here. |
| `apply/Manifest.java` | Interface + `FileManifest` — flushed-per-append `.handler-manifest`. |
| `apply/GitExclude.java` | `.git/info/exclude` line management via `git rev-parse`. Never touches `.gitignore`. |
| `apply/ApplyResult.java` | `APPLIED`/`UNCHANGED`/`SKIPPED_CONFLICT`/`FAILED`. |
| `apply/LocationApplier.java` | Preflight, teardown, write. The only class that mutates a Location. |
| `ReceiveThread.java` | The receive service. Sends the nudge once per changed cycle. |
| `DistributeThread.java` | The distribute service, across all Locations. |
| `IntervalThread.java` | The interval loop: wait, run, repeat. Owns the nudge, the lock, and shutdown. |
| `Handler.java` | Owns both service threads, the startup pass, and shutdown. |
| `cli/HandlerCLI.java` | Arg dispatch and the three subcommands. |
| `Main.java` | Entry point — resolve paths, configure logging, hand off to the CLI. |

Test files live flat in `src/test/java/dev/theagencyhq/handler/tests/`, one `*Test` per production class plus `FakeAgency` and `IntegrationTest`. Frozen contract fixtures live in `src/test/resources/agency/`.

---

### Task 1: Prerequisites, package rename, and `HandlerPaths`

Clears the POC out of the way, renames both modules, and lands the first real class with a passing test so every later task starts from a green build.

**Files:**
- Modify: `project.latte:11` (json dependency version)
- Delete: `src/main/java/dev/theagencyhq/daemon/Main.java`, `Updater.java`, `domain/FilesResponse.java`, `domain/SyncFile.java`
- Delete: `src/test/java/dev/theagencyhq/daemon/tests/UpdaterTest.java`
- Rewrite: `src/main/java/module-info.java`, `src/test/java/module-info.java`
- Create: `src/main/java/dev/theagencyhq/handler/config/HandlerPaths.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/HandlerPathsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `HandlerPaths(Path configFile, Path storeRoot, Path logFile)` with `public static HandlerPaths fromEnvironment()` and `public static HandlerPaths resolve(UnaryOperator<String> env, Path home)`. Every later task takes `HandlerPaths` or a raw `Path` by constructor injection and never reads an environment variable.

`resolve` is **public, not package-private**. Tests live in the flat `dev.theagencyhq.handler.tests` package (the convention the POC established and CLAUDE.md documents), and Java package-private access is scoped to the package name, not the module — a package-private `resolve` is unreachable from the test and will not compile. Making it public is also honest: it is the deliberate injection seam that lets XDG resolution be tested without process-level environment manipulation, which Java cannot do in-process.

- [ ] **Step 1: Confirm the toolchain runs**

`latte` refuses to start unless `java -version` reports 25, which `javaenv` resolves from the repository's `.javaversion` file (contains `25`, tracked in git). Confirm before touching anything else:

```bash
latte --version
```

Expected: `Latte Build System Version [0.4.1]`. If it prints a "You can install Java 25 easily using javaenv" hint instead, `javaenv` is not resolving — check that `.javaversion` still exists at the repository root and that you are running from inside the repository, since `javaenv` walks up from the working directory to find it. Do not work around it by putting a different Java on PATH.

- [ ] **Step 2: Bump the json dependency to 0.4.1**

`@JSONRaw` (spec §14) requires 0.4.1, which is published and current. Confirm and change it:

```bash
curl -sS 'https://api.lattejava.org/api/v1/repository/search?id=org.lattejava:json&latest=true'
```

Expected: `{"id":"org.lattejava:json","versions":["0.4.1"]}`.

In `project.latte`, change line 11:

```
      dependency(id: "org.lattejava:json:0.4.3")
```

Leave the `test-compile` group untouched.

- [ ] **Step 3: Commit the existing staged scaffolding as a baseline**

The build config, `.claude/rules/`, `CLAUDE.md`, and the design doc are staged but were never committed, and the POC sources are staged alongside them. Commit everything except the POC sources so later diffs are readable:

```bash
git reset src/main/java src/test/java
git add .javaversion project.latte docs/ idea.md
git status --short          # confirm no src/ paths are staged before committing
git commit -m "chore: project scaffolding, conventions, and core sync design"
```

`.javaversion`, `.claude/`, `.gitignore`, `.idea/`, `handler.iml`, and `CLAUDE.md` are already staged and ride along in this commit. The `git status --short` check is worth the two seconds — the whole point of this step is that no POC source lands.

- [ ] **Step 4: Delete the POC**

The POC is superseded in full (spec §5 header). It is unstaged after Step 3 and uncommitted, so plain deletion is enough:

```bash
rm -rf src/main/java/dev/theagencyhq/daemon src/test/java/dev/theagencyhq/daemon
ls src/main/java src/test/java
```

Expected: only `module-info.java` remains in each.

- [ ] **Step 5: Rewrite both `module-info.java` files**

`src/main/java/module-info.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
module dev.theagencyhq.handler {
  requires java.logging;
  requires java.net.http;
  requires static org.lattejava.json;

  exports dev.theagencyhq.handler.config;
}
```

`src/test/java/module-info.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
module dev.theagencyhq.handler.tests {
  requires dev.theagencyhq.handler;
  requires java.logging;
  requires org.lattejava.http;
  requires org.testng;

  opens dev.theagencyhq.handler.tests to org.testng;
}
```

`requires java.logging` and `requires java.net.http` land now even though nothing uses them until Tasks 6 and 15 — they are named in spec §12 and adding them once avoids touching this file twice. `org.lattejava.json` stays `requires static`: the annotation processor is compile-time only and the module must not require it at runtime.

- [ ] **Step 6: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/HandlerPathsTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.config.HandlerPaths;

public class HandlerPathsTest {
  private static final Path HOME = Path.of("/home/dev");

  @Test
  public void blankAndRelativeXDGValuesFallBackToDefaults() {
    // The XDG spec says a value that is not an absolute path must be ignored
    HandlerPaths paths = HandlerPaths.resolve(Map.of("XDG_CONFIG_HOME", "  ",
                                                     "XDG_DATA_HOME", "relative/share",
                                                     "XDG_STATE_HOME", "")::get, HOME);

    Assert.assertEquals(paths.configFile(), Path.of("/home/dev/.config/the-agency-hq/handler.json"));
    Assert.assertEquals(paths.storeRoot(), Path.of("/home/dev/.local/share/the-agency-hq/briefs"));
    Assert.assertEquals(paths.logFile(), Path.of("/home/dev/.local/state/the-agency-hq/handler.log"));
  }

  @Test
  public void defaultsWhenNoXDGVariablesAreSet() {
    HandlerPaths paths = HandlerPaths.resolve(Map.<String, String>of()::get, HOME);

    Assert.assertEquals(paths.configFile(), Path.of("/home/dev/.config/the-agency-hq/handler.json"));
    Assert.assertEquals(paths.storeRoot(), Path.of("/home/dev/.local/share/the-agency-hq/briefs"));
    Assert.assertEquals(paths.logFile(), Path.of("/home/dev/.local/state/the-agency-hq/handler.log"));
  }

  @Test
  public void xdgVariablesOverrideDefaults() {
    HandlerPaths paths = HandlerPaths.resolve(Map.of("XDG_CONFIG_HOME", "/etc/xdg",
                                                     "XDG_DATA_HOME", "/var/data",
                                                     "XDG_STATE_HOME", "/var/state")::get, HOME);

    Assert.assertEquals(paths.configFile(), Path.of("/etc/xdg/the-agency-hq/handler.json"));
    Assert.assertEquals(paths.storeRoot(), Path.of("/var/data/the-agency-hq/briefs"));
    Assert.assertEquals(paths.logFile(), Path.of("/var/state/the-agency-hq/handler.log"));
  }
}
```

`Map::get` as the `UnaryOperator<String>` is the whole point of the seam — it tests XDG resolution with no process environment manipulation, which Java cannot do in-process (spec §6.1).

- [ ] **Step 7: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.HandlerPathsTest`

Expected: FAIL at compilation — `package dev.theagencyhq.handler.config does not exist`.

- [ ] **Step 8: Write the implementation**

Create `src/main/java/dev/theagencyhq/handler/config/HandlerPaths.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.config;

import module java.base;

/**
 * The three filesystem locations the Handler owns, resolved once in {@code Main} and injected everywhere else so tests
 * never touch the real home directory.
 *
 * @author Brian Pontarelli
 */
public record HandlerPaths(Path configFile, Path storeRoot, Path logFile) {
  private static final String VENDOR_DIRECTORY = "the-agency-hq";

  /**
   * Resolves the paths from the process environment. This is the only place in the Handler that reads an environment
   * variable.
   *
   * @return The resolved paths.
   */
  public static HandlerPaths fromEnvironment() {
    return resolve(System::getenv, Path.of(System.getProperty("user.home")));
  }

  /**
   * Resolves the paths against an arbitrary environment lookup and home directory. This is the injection seam that
   * makes XDG resolution testable — Java cannot modify its own process environment.
   *
   * @param env  Looks up an environment variable by name, returning null when it is unset.
   * @param home The user's home directory.
   * @return The resolved paths.
   */
  public static HandlerPaths resolve(UnaryOperator<String> env, Path home) {
    Path config = base(env, "XDG_CONFIG_HOME", home.resolve(".config"));
    Path data = base(env, "XDG_DATA_HOME", home.resolve(Path.of(".local", "share")));
    Path state = base(env, "XDG_STATE_HOME", home.resolve(Path.of(".local", "state")));

    return new HandlerPaths(config.resolve(VENDOR_DIRECTORY).resolve("handler.json"),
                            data.resolve(VENDOR_DIRECTORY).resolve("briefs"),
                            state.resolve(VENDOR_DIRECTORY).resolve("handler.log"));
  }

  private static Path base(UnaryOperator<String> env, String variable, Path fallback) {
    String value = env.apply(variable);
    if (value == null || value.isBlank()) {
      return fallback;
    }

    // The XDG spec requires that a value which is not an absolute path be ignored entirely
    Path path = Path.of(value.trim());
    return path.isAbsolute() ? path : fallback;
  }
}
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.HandlerPathsTest`

Expected: PASS, 3 tests. If the build fails resolving `org.lattejava:json`, the version bump in Step 2 did not land — go back.

- [ ] **Step 10: Commit**

```bash
git add project.latte src/main/java src/test/java
git commit -m "feat: rename to dev.theagencyhq.handler and add XDG path resolution"
```

---

### Task 2: `HandlerConfig` and `ConfigLoader`

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/config/HandlerConfig.java`
- Create: `src/main/java/dev/theagencyhq/handler/config/ConfigLoader.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/HandlerConfigTest.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/ConfigLoaderTest.java`

**Interfaces:**
- Consumes: `HandlerPaths` from Task 1.
- Produces:
  - `HandlerConfig(String startDirectory, List<String> excludeDirectories, String theAgencyURL, String accessToken, String refreshToken, int receiveIntervalSeconds, int distributeIntervalSeconds)` — an `@JSON` record, all normalization in the compact constructor. `startDirectory` is a `String` on the wire but normalized to an absolute, `~`-expanded, normalized path string; `startDirectoryPath()` returns it as a `Path`.
  - `HandlerConfig.DEFAULT_EXCLUDE_DIRECTORIES` — `List.of("build", "node_modules", "output", ".*")`.
  - `ConfigLoader(HandlerPaths paths, UnaryOperator<String> env)` with `HandlerConfig load()`.
  - `ConfigLoader.MalformedConfigException extends RuntimeException`.

Read spec §6.2 and §6.3 before starting.

- [ ] **Step 1: Write the failing `HandlerConfig` test**

Create `src/test/java/dev/theagencyhq/handler/tests/HandlerConfigTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.config.HandlerConfig;

public class HandlerConfigTest {
  @Test
  public void absentExcludeDirectoriesBecomeTheDefaults() {
    HandlerConfig config = config(null, null, 0, 0);

    Assert.assertEquals(config.excludeDirectories(), List.of("build", "node_modules", "output", ".*"));
  }

  @Test(dataProvider = "intervals")
  public void intervalsDefaultAndClamp(int receive, int distribute, int expectedReceive, int expectedDistribute) {
    HandlerConfig config = config(null, null, receive, distribute);

    Assert.assertEquals(config.receiveIntervalSeconds(), expectedReceive);
    Assert.assertEquals(config.distributeIntervalSeconds(), expectedDistribute);
  }

  @DataProvider
  public Object[][] intervals() {
    return new Object[][]{
        {0, 0, 300, 60},        // absent or zero becomes the default
        {-5, -1, 300, 60},      // negative is treated as absent
        {1, 9, 10, 10},         // anything below 10 is clamped to 10
        {600, 30, 600, 30}      // valid values pass through untouched
    };
  }

  @Test
  public void startDirectoryTildeExpandsAndNormalizes() {
    HandlerConfig config = config("~/dev/../dev/projects", null, 0, 0);
    Path expected = Path.of(System.getProperty("user.home"), "dev", "projects");

    Assert.assertEquals(config.startDirectoryPath(), expected);
    Assert.assertTrue(config.startDirectoryPath().isAbsolute());
  }

  @Test
  public void suppliedExcludeDirectoriesAreTrimmed() {
    HandlerConfig config = new HandlerConfig(null, List.of("  build  ", "node_modules", " .* "), null, null, null,
                                             0, 0);

    Assert.assertEquals(config.excludeDirectories(), List.of("build", "node_modules", ".*"));
  }

  @Test
  public void theAgencyURLLosesItsTrailingSlash() {
    Assert.assertEquals(config(null, "http://localhost:8080/", 0, 0).theAgencyURL(), "http://localhost:8080");
    Assert.assertEquals(config(null, "http://localhost:8080", 0, 0).theAgencyURL(), "http://localhost:8080");
  }

  @Test
  public void tildeAloneExpandsToHome() {
    Assert.assertEquals(config("~", null, 0, 0).startDirectoryPath(),
                        Path.of(System.getProperty("user.home")));
  }

  private HandlerConfig config(String startDirectory, String theAgencyURL, int receive, int distribute) {
    return new HandlerConfig(startDirectory, null, theAgencyURL, null, null, receive, distribute);
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.HandlerConfigTest`

Expected: FAIL at compilation — `cannot find symbol: class HandlerConfig`.

- [ ] **Step 3: Implement `HandlerConfig`**

Create `src/main/java/dev/theagencyhq/handler/config/HandlerConfig.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.config;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.config.internal.HandlerConfigJSON;

/**
 * The contents of {@code handler.json}. Every value is normalized in the compact constructor, so no caller ever has to
 * defend against a raw form.
 *
 * @author Brian Pontarelli
 */
@JSON
public record HandlerConfig(String startDirectory, List<String> excludeDirectories, String theAgencyURL,
                            String accessToken, String refreshToken, int receiveIntervalSeconds,
                            int distributeIntervalSeconds) {
  public static final int DEFAULT_DISTRIBUTE_INTERVAL_SECONDS = 60;
  public static final List<String> DEFAULT_EXCLUDE_DIRECTORIES = List.of("build", "node_modules", "output", ".*", "Library", "OrbStack");
  public static final int DEFAULT_RECEIVE_INTERVAL_SECONDS = 300;
  public static final String DEFAULT_THE_AGENCY_URL = "http://localhost:8080";
  public static final int MINIMUM_INTERVAL_SECONDS = 10;

  public HandlerConfig {
    startDirectory = expandHome(startDirectory);
    excludeDirectories = excludeDirectories == null ? DEFAULT_EXCLUDE_DIRECTORIES
                                                    : excludeDirectories.stream().map(String::trim).toList();
    theAgencyURL = theAgencyURL == null || theAgencyURL.isBlank() ? DEFAULT_THE_AGENCY_URL
                                                                  : stripTrailingSlash(theAgencyURL.trim());
    accessToken = accessToken == null ? "" : accessToken.trim();
    refreshToken = refreshToken == null ? "" : refreshToken.trim();
    receiveIntervalSeconds = interval(receiveIntervalSeconds, DEFAULT_RECEIVE_INTERVAL_SECONDS);
    distributeIntervalSeconds = interval(distributeIntervalSeconds, DEFAULT_DISTRIBUTE_INTERVAL_SECONDS);
  }

  public static HandlerConfig fromJSON(byte[] json) {
    return HandlerConfigJSON.fromJSON(json);
  }

  private static String expandHome(String value) {
    String directory = value == null || value.isBlank() ? "~" : value.trim();
    if (directory.equals("~")) {
      directory = System.getProperty("user.home");
    } else if (directory.startsWith("~/")) {
      directory = System.getProperty("user.home") + directory.substring(1);
    }

    return Path.of(directory).toAbsolutePath().normalize().toString();
  }

  private static int interval(int value, int fallback) {
    if (value <= 0) {
      return fallback;
    }

    return Math.max(value, MINIMUM_INTERVAL_SECONDS);
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /**
   * @return The normalized {@link #startDirectory()} as a {@link Path}.
   */
  public Path startDirectoryPath() {
    return Path.of(startDirectory);
  }

  public String toJSON() {
    return HandlerConfigJSON.toJSON(this);
  }

  /**
   * @return The same JSON as {@link #toJSON()}, indented two spaces per level with one member per line. This is what
   *     the default config file is written with — it is meant to be opened and edited by hand.
   */
  public String toPrettyString() {
    return HandlerConfigJSON.toPrettyString(this);
  }
}
```

The generated class is `<package>.internal.<Type>JSON` — the annotation processor puts it in an `internal` subpackage, and the record hand-writes thin delegates. This is the pattern the POC's `FilesResponse` used; follow it for every `@JSON` type in this plan.

- [ ] **Step 4: Run it to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.HandlerConfigTest`

Expected: PASS, 9 tests (6 methods, one with 4 data rows). Both halves of every normalization are covered — in particular `excludeDirectories` is tested for the null-becomes-default case *and* the entries-are-trimmed case, so deleting either branch fails a test.

- [ ] **Step 5: Write the failing `ConfigLoader` test**

Create `src/test/java/dev/theagencyhq/handler/tests/ConfigLoaderTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.config;

import module java.base;

/**
 * Reads {@code handler.json}, creating it with defaults when it is absent. A malformed file is fatal — guessing at a
 * developer's intent here would silently sync from the wrong Agency.
 *
 * @author Brian Pontarelli
 */
public class ConfigLoader {
  public static final String START_DIRECTORY_VARIABLE = "THE_AGENCY_HQ_START_DIRECTORY";
  private static final System.Logger LOG = System.getLogger(ConfigLoader.class.getName());
  private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");

  private final UnaryOperator<String> env;
  private final HandlerPaths paths;

  public ConfigLoader(HandlerPaths paths, UnaryOperator<String> env) {
    this.paths = paths;
    this.env = env;
  }

  public HandlerConfig load() {
    Path configFile = paths.configFile();
    HandlerConfig config;
    if (Files.isRegularFile(configFile)) {
      config = parse(configFile);
    } else {
      config = write(configFile);
    }

    String override = env.apply(START_DIRECTORY_VARIABLE);
    if (override != null && !override.isBlank()) {
      LOG.log(System.Logger.Level.DEBUG, "Start directory overridden by [{0}]", START_DIRECTORY_VARIABLE);
      config = new HandlerConfig(override, config.excludeDirectories(), config.theAgencyURL(), config.accessToken(),
                                 config.refreshToken(), config.receiveIntervalSeconds(),
                                 config.distributeIntervalSeconds());
    }

    return config;
  }

  private HandlerConfig parse(Path configFile) {
    try {
      return HandlerConfig.fromJSON(Files.readAllBytes(configFile));
    } catch (IOException e) {
      throw new MalformedConfigException("Unable to read the config file [" + configFile + "]", e);
    } catch (RuntimeException e) {
      throw new MalformedConfigException("Unable to parse the config file [" + configFile + "]: " + e.getMessage(), e);
    }
  }

  private HandlerConfig write(Path configFile) {
    // Every field is null or zero, so the compact constructor fills in the complete default set
    HandlerConfig config = new HandlerConfig(null, null, null, null, null, 0, 0);
    try {
      Files.createDirectories(configFile.getParent());
      // Pretty-printed, and newline-terminated: this file exists to be opened and edited by a developer
      Files.writeString(configFile, config.toPrettyString() + "\n");
      Files.setPosixFilePermissions(configFile, OWNER_READ_WRITE);
      LOG.log(System.Logger.Level.INFO, "Wrote a default config file at [{0}]", configFile);
    } catch (IOException e) {
      throw new MalformedConfigException("Unable to create a default config file at [" + configFile + "]", e);
    }

    return config;
  }

  public static class MalformedConfigException extends RuntimeException {
    public MalformedConfigException(String message) {
      super(message);
    }

    public MalformedConfigException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.ConfigLoaderTest`

Expected: FAIL at compilation — `cannot find symbol: class ConfigLoader`.

- [ ] **Step 7: Implement `ConfigLoader`**

Create `src/main/java/dev/theagencyhq/handler/config/ConfigLoader.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.config;

import module java.base;

/**
 * Reads {@code handler.json}, creating it with defaults when it is absent. A malformed file is fatal — guessing at a
 * developer's intent here would silently sync from the wrong Agency.
 *
 * @author Brian Pontarelli
 */
public class ConfigLoader {
  public static final String START_DIRECTORY_VARIABLE = "THE_AGENCY_HQ_START_DIRECTORY";
  private static final System.Logger LOG = System.getLogger(ConfigLoader.class.getName());
  private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");

  private final UnaryOperator<String> env;
  private final HandlerPaths paths;

  public ConfigLoader(HandlerPaths paths, UnaryOperator<String> env) {
    this.paths = paths;
    this.env = env;
  }

  public HandlerConfig load() {
    Path configFile = paths.configFile();
    HandlerConfig config;
    if (Files.isRegularFile(configFile)) {
      config = parse(configFile);
    } else {
      config = write(configFile);
    }

    String override = env.apply(START_DIRECTORY_VARIABLE);
    if (override != null && !override.isBlank()) {
      LOG.log(System.Logger.Level.DEBUG, "Start directory overridden by [{0}]", START_DIRECTORY_VARIABLE);
      config = new HandlerConfig(override, config.excludeDirectories(), config.theAgencyURL(), config.accessToken(),
                                 config.refreshToken(), config.receiveIntervalSeconds(),
                                 config.distributeIntervalSeconds());
    }

    return config;
  }

  private HandlerConfig parse(Path configFile) {
    try {
      return HandlerConfig.fromJSON(Files.readAllBytes(configFile));
    } catch (IOException e) {
      throw new MalformedConfigException("Unable to read the config file [" + configFile + "]", e);
    } catch (RuntimeException e) {
      throw new MalformedConfigException("Unable to parse the config file [" + configFile + "]: " + e.getMessage(), e);
    }
  }

  private HandlerConfig write(Path configFile) {
    // Every field is null or zero, so the compact constructor fills in the complete default set
    HandlerConfig config = new HandlerConfig(null, null, null, null, null, 0, 0);
    try {
      Files.createDirectories(configFile.getParent());
      Files.writeString(configFile, config.toJSON());
      Files.setPosixFilePermissions(configFile, OWNER_READ_WRITE);
      LOG.log(System.Logger.Level.INFO, "Wrote a default config file at [{0}]", configFile);
    } catch (IOException e) {
      throw new MalformedConfigException("Unable to create a default config file at [" + configFile + "]", e);
    }

    return config;
  }

  public static class MalformedConfigException extends RuntimeException {
    public MalformedConfigException(String message) {
      super(message);
    }

    public MalformedConfigException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
```

- [ ] **Step 8: Run both config tests to verify they pass**

Run: `latte test --test=dev.theagencyhq.handler.tests.ConfigLoaderTest`
Then: `latte test`

Expected: PASS. The full run is worth it here — it is the first task with more than one test class, and it confirms nothing in Task 1 regressed.

- [ ] **Step 9: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add handler.json config loading with normalization and defaults"
```

---

### Task 3: Brief models and verbatim wire-byte capture

The riskiest task in the plan — everything downstream trusts that `Brief.raw()` is byte-identical to what the Agency sent. Read spec §4, §7.3, and §14 before starting.

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/brief/Organization.java`
- Create: `src/main/java/dev/theagencyhq/handler/brief/BriefFile.java`
- Create: `src/main/java/dev/theagencyhq/handler/brief/Brief.java`
- Create: `src/main/java/dev/theagencyhq/handler/agency/BriefingResponse.java`
- Modify: `src/main/java/module-info.java` (add `exports dev.theagencyhq.handler.agency;` and `exports dev.theagencyhq.handler.brief;`)
- Create: `src/test/resources/agency/briefing-updated.json`
- Create: `src/test/resources/agency/briefing-tricky.json`
- Create: `src/test/resources/agency/briefing-compact.json`
- Test: `src/test/java/dev/theagencyhq/handler/tests/BriefTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `Organization(String id, String name)` — `@JSON` record, both trimmed.
  - `BriefFile(String path, String encoding, String mode, String content, String checksum, List<String> missionTypes)` — `@JSON` record. Defaults applied in the compact constructor: `encoding` → `"text"`, `mode` → `"r--------"`, `missionTypes` → empty list with entries trimmed and lowercased. Methods: `byte[] decoded()` and `Set<PosixFilePermission> posixMode()`, both throwing `IllegalArgumentException` on bad input.

**`mode` is symbolic, not octal** (§4). `posixMode()` is `PosixFilePermissions.fromString(mode)` in a try/catch and nothing more — no octal parse, no range check, no bit shifting. Do not reinstate any of those: the JDK's grammar is exactly the accepted set, and the `s`/`S`/`t`/`T` spellings of setuid, setgid, and sticky must keep failing, because `PosixFilePermission` cannot represent them and `Files.setPosixFilePermissions` could not apply them.
  - `Brief(String raw, String checksum, Organization organization, int version, List<BriefFile> files)` — `@JSON` record whose `raw` component carries `@JSONRaw`. Methods: `static Brief fromJSON(byte[] json)`, `byte[] rawBytes()`.
  - `BriefingResponse(List<String> organizationIds, List<Brief> briefs)` — `@JSON` record with `static BriefingResponse fromJSON(byte[] json)`.

**Why `BriefingResponse` lands here and not in Task 5.** Verified against the processor's own codegen tests (2026-07-29): the generated `<Type>JSON` class exposes **only** `fromJSON(String)` and `fromJSON(byte[])`. There is no `fromJSONList`, no `fromJSONArray`, and no other collection entry point. A bare JSON array of Briefs therefore cannot be deserialized directly, so proving the raw capture requires the wrapper type that owns the `briefs` array. The record has no dependency on `AgencyClient`, so creating it now costs nothing; Task 5 builds the rest of the `agency` package around it.

**Implementation note — a deliberate reading of §8.3.** The spec assigns content decoding and mode parsing to `BriefPlanner` (§8.3 steps 2–3), but `ReceiveThread` also has to decode every file to verify its checksum (§7.5). Rather than implement the same decode twice, `decoded()` and `posixMode()` live on `BriefFile` and both callers use them. The planner still owns the *decision* to fail a plan when they throw. Flag this in review if the split is unwanted.

- [ ] **Step 1: Create the three fixtures**

These are the frozen contract with The Agency (spec §13) — a change to them is a visible API change. Write them exactly as shown, including whitespace, because Task 3's tests assert on their literal text.

`src/test/resources/agency/briefing-updated.json`:

```json
{
  "organizationIds": ["42", "43"],
  "briefs": [
    {
      "checksum": "opaque-42-73",
      "organization": { "id": "42", "name": "Acme2" },
      "version": 73,
      "files": [
        {
          "path": ".claude/rules/foo.md",
          "encoding": "text",
          "mode": "r--------",
          "content": "For Claude",
          "checksum": "7b0464d7d419e8e21902270f71ec5e809ba2d1af68aea0df38f4e4913366a1b8",
          "missionTypes": ["Web", "Library"]
        }
      ]
    },
    {
      "checksum": "opaque-43-5",
      "organization": { "id": "43", "name": "Acme" },
      "version": 5,
      "files": []
    }
  ]
}
```

`src/test/resources/agency/briefing-tricky.json` — the case that breaks a naive brace scanner:

```json
{
  "organizationIds": ["44"],
  "briefs": [
    {
      "checksum": "opaque-44-1",
      "organization": { "id": "44", "name": "Braces { } And \"Quotes\"" },
      "version": 1,
      "files": [
        {
          "path": ".claude/rules/tricky.md",
          "content": "A closing brace } then an escaped quote \" then a backslash \\\\ then éü中文",
          "checksum": "ignored-by-this-test"
        }
      ]
    }
  ]
}
```

`src/test/resources/agency/briefing-compact.json` — single line, so the expected `raw` can be written as a literal:

```json
{"organizationIds":["45"],"briefs":[{"checksum":"c","organization":{"id":"45","name":"N"},"version":2,"files":[]}]}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/BriefTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.agency.BriefingResponse;
import dev.theagencyhq.handler.brief.Brief;
import dev.theagencyhq.handler.brief.BriefFile;
import dev.theagencyhq.handler.brief.Organization;

public class BriefTest {
  @Test
  public void base64ContentDecodesToRawBytes() {
    BriefFile file = new BriefFile("logo.png", "base64", null, "AAEC", null, null);

    Assert.assertEquals(file.decoded(), new byte[]{0, 1, 2});
  }

  @Test(dataProvider = "fixtures")
  public void capturedRawIsALiteralSubstringOfTheWire(String fixture, int expectedBriefs) throws IOException {
    byte[] wire = fixture(fixture);
    String wireText = new String(wire, StandardCharsets.UTF_8);
    List<Brief> briefs = briefs(wire);

    // Without this the loop below is vacuous, and a fixture that stopped yielding Briefs would silently lose all of
    // its coverage while still reporting green
    Assert.assertEquals(briefs.size(), expectedBriefs, "Fixture [" + fixture + "] yielded the wrong Brief count");

    for (Brief brief : briefs) {
      Assert.assertTrue(brief.raw().startsWith("{") && brief.raw().endsWith("}"),
                        "raw must span brace to brace: [" + brief.raw() + "]");
      Assert.assertTrue(wireText.contains(brief.raw()),
                        "raw is not verbatim from the wire, so it was re-serialized: [" + brief.raw() + "]");
    }
  }

  @Test
  public void compactFixtureCapturesTheExactExpectedText() throws IOException {
    // An exact-text positive control against off-by-one framing. Note it has no capture-vs-re-serialize power on its
    // own: the generated writer emits keys in record order with no whitespace, which is exactly this fixture's shape,
    // so a re-serializing implementation would also pass. That discrimination lives in the substring test running
    // against the two pretty-printed fixtures — which is why those two must stay pretty-printed.
    String expected = "{\"checksum\":\"c\",\"organization\":{\"id\":\"45\",\"name\":\"N\"},\"version\":2,\"files\":[]}";
    List<Brief> briefs = briefs(fixture("briefing-compact.json"));

    Assert.assertEquals(briefs.size(), 1);
    Assert.assertEquals(briefs.getFirst().raw(), expected);
  }

  @Test
  public void defaultsAreAppliedWhenFieldsAreAbsent() {
    BriefFile file = new BriefFile(".claude/a.md", null, null, "x", null, null);

    Assert.assertEquals(file.encoding(), "text");
    Assert.assertEquals(file.mode(), "r--------");
    Assert.assertEquals(file.missionTypes(), List.of());
  }

  @DataProvider
  public Object[][] fixtures() {
    return new Object[][]{{"briefing-updated.json", 2}, {"briefing-tricky.json", 1}, {"briefing-compact.json", 1}};
  }

  @DataProvider
  public Object[][] invalidOrganizationIds() {
    return new Object[][]{{"../evil"}, {"/absolute"}, {"a/b"}, {"."}, {".."}};
  }

  @Test
  public void missionTypesAreTrimmedAndLowercased() {
    BriefFile file = new BriefFile(".claude/a.md", null, null, "x", null, List.of("  Web ", "LIBRARY"));

    Assert.assertEquals(file.missionTypes(), List.of("web", "library"));
  }

  @Test(dataProvider = "modes")
  public void modeParsesSymbolicAndRejectsAnythingElse(String mode, String expected) {
    BriefFile file = new BriefFile(".claude/a.md", null, mode, "x", null, null);
    if (expected == null) {
      Assert.expectThrows(IllegalArgumentException.class, file::posixMode);
    } else {
      Assert.assertEquals(file.posixMode(), PosixFilePermissions.fromString(expected));
    }
  }

  @DataProvider
  public Object[][] modes() {
    return new Object[][]{
        {"r--------", "r--------"},
        {"rw-------", "rw-------"},
        {"rwxr-xr-x", "rwxr-xr-x"},
        {"---------", "---------"},
        {"rwxrwxrwx", "rwxrwxrwx"},
        {"rwsr-xr-x", null},      // setuid - PosixFilePermission cannot represent it, so it must not be accepted
        {"rwxr-sr-x", null},      // setgid
        {"rwxrwxrwt", null},      // sticky
        {"0400", null},           // octal is no longer the wire format
        {"rw-r--r", null},        // too short
        {"rw-r--r---", null},     // too long
        {"rw-r--rw", null},       // wrong length after a plausible-looking prefix
        {"xwrxwrxwr", null},      // right alphabet, wrong positions
        {"not-a-mode", null}
    };
  }

  @Test
  public void organizationIdAcceptsANormalOrEmptyValue() {
    Assert.assertEquals(new Organization("42", "Org").id(), "42");
    Assert.assertEquals(new Organization("", "Org").id(), "");
    Assert.assertEquals(new Organization(null, "Org").id(), "");
  }

  @Test(dataProvider = "invalidOrganizationIds")
  public void organizationIdMustBeASinglePathSegment(String id) {
    // Organization.id is server-controlled and reaches FileBriefStore's storeRoot.resolve(). An absolute or
    // multi-segment id is an arbitrary-write primitive outside the store, so the compact constructor must reject it.
    Assert.expectThrows(IllegalArgumentException.class, () -> new Organization(id, "Org"));
  }

  @Test(dataProvider = "fixtures")
  public void reparsingRawYieldsAnEqualBrief(String fixture, int expectedBriefs) throws IOException {
    // The strongest available check: raw is a complete, valid, self-describing Brief document. Because the reparsed
    // Brief captures the same text into its own raw component, the two records must be exactly equal.
    List<Brief> briefs = briefs(fixture(fixture));

    // Without this the loop below is vacuous, and a fixture that stopped yielding Briefs would silently lose all of
    // its coverage while still reporting green
    Assert.assertEquals(briefs.size(), expectedBriefs, "Fixture [" + fixture + "] yielded the wrong Brief count");

    for (Brief brief : briefs) {
      Assert.assertEquals(Brief.fromJSON(brief.rawBytes()), brief);
    }
  }

  private List<Brief> briefs(byte[] wire) {
    // @JSONRaw captures at any nesting depth, so the Briefs come out of the response wrapper already carrying their own
    // verbatim text
    return BriefingResponse.fromJSON(wire).briefs();
  }

  private byte[] fixture(String name) throws IOException {
    // Read from the source tree rather than the module's resources: the tests already assume the project root is the
    // working directory, and it keeps JPMS resource encapsulation out of the picture.
    return Files.readAllBytes(Path.of("src/test/resources/agency", name));
  }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.BriefTest`

Expected: FAIL at compilation — `package dev.theagencyhq.handler.brief does not exist`.

- [ ] **Step 4: Implement `Organization`**

Create `src/main/java/dev/theagencyhq/handler/brief/Organization.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;
import module org.lattejava.json;

/**
 * The Organization that published a Brief.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Organization(String id, String name) {
  public Organization {
    id = id == null ? "" : id.trim();
    name = name == null ? "" : name.trim();
  }
}
```

- [ ] **Step 5: Implement `BriefFile`**

Create `src/main/java/dev/theagencyhq/handler/brief/BriefFile.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;
import module org.lattejava.json;

/**
 * One file in a Brief. The Handler never interprets the content beyond decoding it to bytes.
 *
 * @author Brian Pontarelli
 */
@JSON
public record BriefFile(String path, String encoding, String mode, String content, String checksum,
                        List<String> missionTypes) {
  public static final String DEFAULT_ENCODING = "text";
  public static final String DEFAULT_MODE = "r--------";
  public static final String ENCODING_BASE64 = "base64";

  public BriefFile {
    path = path == null ? "" : path.trim();
    encoding = encoding == null || encoding.isBlank() ? DEFAULT_ENCODING
                                                      : encoding.trim().toLowerCase(Locale.ROOT);
    mode = mode == null || mode.isBlank() ? DEFAULT_MODE : mode.trim();
    content = content == null ? "" : content;
    checksum = checksum == null ? "" : checksum.trim().toLowerCase(Locale.ROOT);
    missionTypes = missionTypes == null ? List.of()
                                       : missionTypes.stream().map(t -> t.trim().toLowerCase(Locale.ROOT)).toList();
  }

  /**
   * @return The content decoded to bytes according to {@link #encoding()}.
   * @throws IllegalArgumentException If the encoding is unknown or base64 content is not valid base64.
   */
  public byte[] decoded() {
    return switch (encoding) {
      case DEFAULT_ENCODING -> content.getBytes(StandardCharsets.UTF_8);
      case ENCODING_BASE64 -> Base64.getDecoder().decode(content);
      default -> throw new IllegalArgumentException("Unknown encoding [" + encoding + "] for file [" + path + "]");
    };
  }

  /**
   * @return The symbolic {@link #mode()} as a POSIX permission set.
   * @throws IllegalArgumentException If the mode is not nine {@code rwx-} characters in {@code ls -l} order.
   */
  public Set<PosixFilePermission> posixMode() {
    try {
      // The JDK owns the entire grammar: exactly nine characters, fixed alphabet, fixed positions. setuid, setgid, and
      // sticky are spelled s/S/t/T in this notation and are rejected here, because PosixFilePermission has no constant
      // for them and Files.setPosixFilePermissions could not apply them anyway. Failing loudly beats accepting a bit
      // that would be silently dropped.
      return PosixFilePermissions.fromString(mode);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Mode [" + mode + "] for file [" + path + "] is not a POSIX permission string such as [rw-r--r--]", e);
    }
  }
}
```

- [ ] **Step 6: Implement `Brief`**

Create `src/main/java/dev/theagencyhq/handler/brief/Brief.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.brief.internal.BriefJSON;

/**
 * A published Brief. The {@code raw} component is populated by the parser with the verbatim JSON text of this object,
 * from its opening brace through its matching closing brace, so the Handler can store exactly what the Agency sent
 * without ever re-serializing it.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Brief(@JSONRaw String raw, String checksum, Organization organization, int version,
                    List<BriefFile> files) {
  public Brief {
    // Not defaulted to "" — a zero-byte brief.json written from an empty raw would be silently corrupt, whereas a
    // null here is always a programming error and should fail loudly at construction
    Objects.requireNonNull(raw, "A Brief's raw wire text is required");
    checksum = checksum == null ? "" : checksum.trim();
    files = files == null ? List.of() : files;
  }

  public static Brief fromJSON(byte[] json) {
    return BriefJSON.fromJSON(json);
  }

  /**
   * @return The verbatim wire text as UTF-8 bytes. JSON source is required to be valid UTF-8, so this reproduces the
   *     bytes the Agency sent exactly.
   */
  public byte[] rawBytes() {
    return raw.getBytes(StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 7: Implement `BriefingResponse`**

Create `src/main/java/dev/theagencyhq/handler/agency/BriefingResponse.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.agency.internal.BriefingResponseJSON;
import dev.theagencyhq.handler.brief.Brief;

/**
 * The {@code 200} response body. {@code organizationIds} is the complete entitled set, not a delta — that is what makes
 * revocation self-healing without a separate event.
 *
 * @author Brian Pontarelli
 */
@JSON
public record BriefingResponse(List<String> organizationIds, List<Brief> briefs) {
  public BriefingResponse {
    organizationIds = organizationIds == null ? List.of() : organizationIds.stream().map(String::trim).toList();
    briefs = briefs == null ? List.of() : briefs;
  }

  public static BriefingResponse fromJSON(byte[] json) {
    return BriefingResponseJSON.fromJSON(json);
  }
}
```

- [ ] **Step 8: Export both packages**

In `src/main/java/module-info.java`, add to the exports block, keeping it alphabetized:

```java
  exports dev.theagencyhq.handler.agency;
  exports dev.theagencyhq.handler.brief;
  exports dev.theagencyhq.handler.config;
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.BriefTest`

Expected: PASS, 19 tests — 7 `@Test` methods, of which two run across the 3 fixtures (6 invocations) and one across the 9 mode rows, plus 4 single-invocation tests.

`capturedRawIsALiteralSubstringOfTheWire` and `reparsingRawYieldsAnEqualBrief` are the two that matter. If either fails on `briefing-tricky.json` but passes on the others, the capture is mis-handling escapes or braces inside strings — that is a bug in `org.lattejava:json`, not in this project. Stop and report it rather than working around it here.

- [ ] **Step 10: Commit**

```bash
git add src/main/java src/test/java src/test/resources
git commit -m "feat: add Brief models with verbatim wire-byte capture via @JSONRaw"
```

---

### Task 4: `BriefStore`

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/brief/StoredBrief.java`
- Create: `src/main/java/dev/theagencyhq/handler/brief/BriefStore.java`
- Create: `src/main/java/dev/theagencyhq/handler/brief/FileBriefStore.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/BriefStoreTest.java`

**Interfaces:**
- Consumes: `Brief`, `Organization` from Task 3.
- Produces:
  - `StoredBrief(Brief brief, Path path)`, with `String organizationId()` delegating to `brief().organization().id()`.
  - `BriefStore` interface exactly as spec §7.4 declares it: `List<StoredBrief> allCurrent()`, `Optional<StoredBrief> latest(String organizationId)`, `void markRevoked(String organizationId)`, `Set<String> organizationIds()`, `void purge(String organizationId)`, `boolean revoked(String organizationId)`, `void store(Brief brief)`.
  - `FileBriefStore(Path storeRoot)` implementing it, throwing `UncheckedIOException` on I/O failure.

Read spec §7.4 before starting. The `latest` skip-incomplete rule is what makes the whole lock-free receive/distribute handoff safe — get it exactly right.

**`store` sweeps orphaned temp files before it writes** (§7.4). A store that dies between the `SYNC` write and the `ATOMIC_MOVE` leaves a `brief.json.tmp-*` behind, and since no version is ever pruned nothing else would remove it. Two things the sweep must get right, both covered by tests below: it is scoped to the whole Organization rather than just the version being written, because the case that accumulates is a crash on version 5 followed by a successful store of version 6; and it skips files newer than five minutes, because another process running `handler sync` against the same store may be midway through writing one. Every failure in the sweep is logged at DEBUG and swallowed — litter must never fail a store.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/BriefStoreTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;
import java.nio.file.Files;

import dev.theagencyhq.handler.brief.*;

import static org.testng.Assert.*;

public class BriefStoreTest {
  private Path storeRoot;

  @Test
  public void aLeftoverTemporaryFileFromACrashedStoreIsIgnored() throws IOException {
    BriefStore store = store();
    store.store(brief("42", 1));
    // Exactly what a crash between the SYNC write and the ATOMIC_MOVE leaves behind
    Path partial = storeRoot.resolve("42/1/brief.json.tmp-" + UUID.randomUUID());
    Files.writeString(partial, "{ partial");

    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
    assertEquals(Files.readAllBytes(storeRoot.resolve("42/1/brief.json")), brief("42", 1).rawBytes());
    assertTrue(Files.isRegularFile(partial));
  }

  @Test
  public void aRecentTemporaryFileIsLeftAloneBecauseAnotherProcessMayBeWritingIt() throws IOException {
    BriefStore store = store();
    store.store(brief("42", 1));
    // Every subcommand runs against the same store with no IPC, so `handler sync` can be mid-write while the daemon
    // stores. Deleting that file would fail the other process's move.
    Path inFlight = storeRoot.resolve("42/1/brief.json.tmp-" + UUID.randomUUID());
    Files.writeString(inFlight, "{ partial");

    store.store(brief("42", 2));

    assertTrue(Files.isRegularFile(inFlight), "A temp file younger than the age bound must survive the sweep");
  }

  @Test
  public void allCurrentReturnsTheLatestPerOrgAndExcludesRevoked() {
    BriefStore store = store();
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
    BriefStore store = store();
    store.store(brief("42", 3));
    Files.createDirectories(storeRoot.resolve("42/not-a-version"));

    assertEquals(store.latest("42").orElseThrow().brief().version(), 3);
  }

  @Test
  public void latestSkipsADocumentThatFailsToParse() throws IOException {
    BriefStore store = store();
    store.store(brief("42", 1));
    Files.createDirectories(storeRoot.resolve("42/2"));
    Files.writeString(storeRoot.resolve("42/2/brief.json"), "{ this is not json");

    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
  }

  @Test
  public void latestSkipsAVersionWhoseDocumentDisagreesWithItsPath() throws IOException {
    BriefStore store = store();
    store.store(brief("42", 1));
    Files.createDirectories(storeRoot.resolve("42/9"));
    Files.writeString(storeRoot.resolve("42/9/brief.json"), brief("42", 8).raw());

    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
  }

  @Test
  public void latestSkipsAVersionWhoseDocumentNamesADifferentOrganization() throws IOException {
    BriefStore store = store();
    store.store(brief("42", 1));
    Files.createDirectories(storeRoot.resolve("42/2"));
    Files.writeString(storeRoot.resolve("42/2/brief.json"), brief("99", 2).raw());

    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
  }

  @Test
  public void latestSkipsAnIncompleteVersionDirectory() throws IOException {
    BriefStore store = store();
    store.store(brief("42", 1));
    Files.createDirectories(storeRoot.resolve("42/2"));       // created but brief.json never landed

    // The previous version stays live - this is what makes the lock-free handoff safe
    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
  }

  @Test
  public void latestSortsVersionsNumericallyNotLexicographically() {
    BriefStore store = store();
    store.store(brief("42", 9));
    store.store(brief("42", 10));

    // Lexicographic ordering would rank "9" above "10"
    assertEquals(store.latest("42").orElseThrow().brief().version(), 10);
  }

  @Test
  public void purgeRemovesTheOrganizationEntirely() {
    BriefStore store = store();
    store.store(brief("42", 1));
    store.markRevoked("42");

    store.purge("42");

    Assert.assertFalse(Files.exists(storeRoot.resolve("42")));
    assertEquals(store.organizationIds(), Set.of());
  }

  @Test
  public void revocationSurvivesAFreshStoreInstance() {
    FileBriefStore first = store();
    first.store(brief("42", 1));
    first.markRevoked("42");

    assertTrue(store().revoked("42"), "Revocation must be persisted, not held in memory");
  }

  @BeforeMethod
  public void setUp() throws IOException {
    storeRoot = Files.createDirectories(Path.of("build/test/store-" + UUID.randomUUID()));
  }

  @Test
  public void storeIsAtomicAndReplacesACorruptDocument() throws IOException {
    BriefStore store = store();
    store.store(brief("42", 73));
    Path document = storeRoot.resolve("42/73/brief.json");
    Files.writeString(document, "{ truncated");

    store.store(brief("42", 73));

    assertEquals(Files.readString(document), brief("42", 73).raw());
    assertEquals(Files.getPosixFilePermissions(document), PosixFilePermissions.fromString("rw-------"));
    try (Stream<Path> entries = Files.list(storeRoot.resolve("42/73"))) {
      assertEquals(entries.count(), 1, "No temp file may be left behind");
    }
  }

  @Test
  public void storeSweepsStaleTemporaryFilesAcrossEveryVersionOfTheOrganization() throws IOException {
    BriefStore store = store();
    store.store(brief("42", 1));
    store.store(brief("43", 1));
    Files.createDirectories(storeRoot.resolve("42/2"));
    Path targetVersion = stale(storeRoot.resolve("42/2/brief.json.tmp-" + UUID.randomUUID()));
    Path olderVersion = stale(storeRoot.resolve("42/1/brief.json.tmp-" + UUID.randomUUID()));
    Path otherOrganization = stale(storeRoot.resolve("43/1/brief.json.tmp-" + UUID.randomUUID()));

    // Storing version 2 must also clear version 1's litter - a crash there would otherwise leave it forever,
    // because no version is ever pruned
    store.store(brief("42", 2));

    assertFalse(Files.exists(targetVersion));
    assertFalse(Files.exists(olderVersion));
    assertTrue(Files.isRegularFile(otherOrganization), "The sweep is scoped to the Organization being stored");
    assertEquals(store.latest("42").orElseThrow().brief().version(), 2);
    assertEquals(Files.readAllBytes(storeRoot.resolve("42/1/brief.json")), brief("42", 1).rawBytes());
  }

  @Test
  public void storeWritesTheExactWireBytes() throws IOException {
    Brief brief = brief("42", 73);
    store().store(brief);

    assertEquals(Files.readAllBytes(storeRoot.resolve("42/73/brief.json")), brief.rawBytes());
  }

  private Brief brief(String organizationId, int version) {
    String json = """
        {"checksum":"c-%s-%d","organization":{"id":"%s","name":"Org %s"},"version":%d,"files":[]}"""
        .formatted(organizationId, version, organizationId, organizationId, version);
    return Brief.fromJSON(json.getBytes(StandardCharsets.UTF_8));
  }

  /** Writes a partial temp file and backdates it past the sweep's age bound. */
  private Path stale(Path temporary) throws IOException {
    Files.writeString(temporary, "{ partial");
    Files.setLastModifiedTime(temporary, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
    return temporary;
  }

  private FileBriefStore store() {
    return new FileBriefStore(storeRoot);
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.BriefStoreTest`

Expected: FAIL at compilation — `cannot find symbol: class FileBriefStore`.

- [ ] **Step 3: Implement `StoredBrief` and the `BriefStore` interface**

Create `src/main/java/dev/theagencyhq/handler/brief/StoredBrief.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;

/**
 * A Brief read back out of the store, paired with the document it came from.
 *
 * @author Brian Pontarelli
 */
public record StoredBrief(Brief brief, Path path) {
  public String organizationId() {
    return brief.organization().id();
  }

  public int version() {
    return brief.version();
  }
}
```

Create `src/main/java/dev/theagencyhq/handler/brief/BriefStore.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;

/**
 * The immutable, never-pruned local store of Briefs. This is the only handoff between the receive and distribute
 * tasks, so every implementation must make {@link #store} atomic and {@link #latest} skip incomplete versions.
 *
 * @author Brian Pontarelli
 */
public interface BriefStore {
  /**
   * @return The latest Brief for every non-revoked Organization in the store.
   */
  List<StoredBrief> allCurrent();

  /**
   * @param organizationId The Organization id.
   * @return The highest complete version for the Organization, or empty if it has none.
   */
  Optional<StoredBrief> latest(String organizationId);

  void markRevoked(String organizationId);

  Set<String> organizationIds();

  void purge(String organizationId);

  boolean revoked(String organizationId);

  void store(Brief brief);
}
```

- [ ] **Step 4: Implement `FileBriefStore`**

Create `src/main/java/dev/theagencyhq/handler/brief/FileBriefStore.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;

/**
 * The on-disk Brief store, laid out as {@code {storeRoot}/{organizationId}/{version}/brief.json}. No version is ever
 * pruned.
 *
 * @author Brian Pontarelli
 */
public class FileBriefStore implements BriefStore {
  private static final String DOCUMENT = "brief.json";
  private static final System.Logger LOG = System.getLogger(FileBriefStore.class.getName());
  private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");
  private static final String REVOKED_MARKER = ".revoked";
  private static final Duration TEMPORARY_MINIMUM_AGE = Duration.ofMinutes(5);
  private static final String TEMPORARY_PREFIX = DOCUMENT + ".tmp-";

  private final Path storeRoot;

  public FileBriefStore(Path storeRoot) {
    this.storeRoot = storeRoot;
  }

  @Override
  public List<StoredBrief> allCurrent() {
    return organizationIds().stream()
                            .filter(id -> !revoked(id))
                            .map(this::latest)
                            .flatMap(Optional::stream)
                            .sorted(Comparator.comparing(StoredBrief::organizationId))
                            .toList();
  }

  @Override
  public Optional<StoredBrief> latest(String organizationId) {
    Path organizationDirectory = storeRoot.resolve(organizationId);
    if (!Files.isDirectory(organizationDirectory)) {
      return Optional.empty();
    }

    List<Integer> versions = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(organizationDirectory)) {
      for (Path child : children) {
        if (!Files.isDirectory(child)) {
          continue;
        }

        try {
          versions.add(Integer.parseInt(child.getFileName().toString()));
        } catch (NumberFormatException ignored) {
          // Non-numeric directory names are not versions
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to list the store directory [" + organizationDirectory + "]", e);
    }

    versions.sort(Comparator.reverseOrder());
    for (int version : versions) {
      Path document = organizationDirectory.resolve(Integer.toString(version)).resolve(DOCUMENT);
      Optional<StoredBrief> candidate = read(document, organizationId, version);
      if (candidate.isPresent()) {
        return candidate;
      }
    }

    return Optional.empty();
  }

  @Override
  public void markRevoked(String organizationId) {
    Path marker = storeRoot.resolve(organizationId).resolve(REVOKED_MARKER);
    try {
      Files.createDirectories(marker.getParent());
      if (Files.exists(marker)) {
        return;
      }

      Files.createFile(marker);
      LOG.log(System.Logger.Level.INFO, "Marked Organization [{0}] revoked", organizationId);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to mark Organization [" + organizationId + "] revoked", e);
    }
  }

  @Override
  public Set<String> organizationIds() {
    if (!Files.isDirectory(storeRoot)) {
      return Set.of();
    }

    try (DirectoryStream<Path> children = Files.newDirectoryStream(storeRoot)) {
      Set<String> ids = new TreeSet<>();
      for (Path child : children) {
        if (Files.isDirectory(child)) {
          ids.add(child.getFileName().toString());
        }
      }

      return ids;
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to list the store root [" + storeRoot + "]", e);
    }
  }

  @Override
  public void purge(String organizationId) {
    Path organizationDirectory = storeRoot.resolve(organizationId);
    if (!Files.exists(organizationDirectory)) {
      return;
    }

    try (Stream<Path> paths = Files.walk(organizationDirectory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }

      LOG.log(System.Logger.Level.INFO, "Purged Organization [{0}] from the store", organizationId);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to purge Organization [" + organizationId + "]", e);
    }
  }

  @Override
  public boolean revoked(String organizationId) {
    return Files.exists(storeRoot.resolve(organizationId).resolve(REVOKED_MARKER));
  }

  @Override
  public void store(Brief brief) {
    String organizationId = brief.organization().id();
    Path organizationDirectory = storeRoot.resolve(organizationId);
    Path versionDirectory = organizationDirectory.resolve(Integer.toString(brief.version()));
    Path temporary = null;
    try {
      Files.createDirectories(versionDirectory);
      // Before the new temp file exists, so this can never be a candidate for its own sweep
      sweepTemporaries(organizationDirectory);
      temporary = versionDirectory.resolve(TEMPORARY_PREFIX + UUID.randomUUID());
      Files.write(temporary, brief.rawBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
      Files.setPosixFilePermissions(temporary, OWNER_READ_WRITE);
      Files.move(temporary, versionDirectory.resolve(DOCUMENT), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      LOG.log(System.Logger.Level.INFO, "Stored Organization [{0}] version [{1}]", organizationId, brief.version());
    } catch (IOException e) {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // Nothing more can be done - the partial temp file is named uniquely and will never be read
        }
      }

      throw new UncheckedIOException("Unable to store Organization [" + organizationId + "] version [" + brief.version() + "]", e);
    }
  }

  private Optional<StoredBrief> read(Path document, String organizationId, int version) {
    if (!Files.isRegularFile(document)) {
      return Optional.empty();      // created but not yet populated - the previous version stays live
    }

    try {
      Brief brief = Brief.fromJSON(Files.readAllBytes(document));
      if (!brief.organization().id().equals(organizationId) || brief.version() != version) {
        LOG.log(System.Logger.Level.ERROR, "Store document [{0}] disagrees with its path and was skipped", document);
        return Optional.empty();
      }

      return Optional.of(new StoredBrief(brief, document));
    } catch (IOException | RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "Unable to parse store document [" + document + "], skipping it", e);
      return Optional.empty();
    }
  }

  /**
   * Deletes temporary documents orphaned by a store that died between the write and the atomic move. They are
   * invisible to {@link #latest} — it reads only {@code brief.json} — so this is litter rather than a correctness
   * problem, but no version is ever pruned, so nothing else would ever remove it.
   *
   * <p>The whole Organization is swept, not just the version being written. A crash storing version 5 followed by a
   * successful store of version 6 would otherwise leave version 5's partial file behind forever. Stores happen only
   * when the Agency actually returns a Brief, so listing an Organization's version directories is cheap at that rate.
   *
   * <p>Only files older than {@link #TEMPORARY_MINIMUM_AGE} are removed. Every subcommand runs against the same store
   * with no IPC (§2), so {@code handler sync} can run while the daemon does, and deleting a temp file another process
   * is midway through writing would fail that process's move. The age bound puts that out of reach — a single
   * {@code SYNC} write of a few kilobytes is many orders of magnitude faster than the threshold.
   *
   * <p>Every failure here is logged and swallowed. Litter is never worth failing a store over.
   */
  private void sweepTemporaries(Path organizationDirectory) {
    Instant cutoff = Instant.now().minus(TEMPORARY_MINIMUM_AGE);
    int removed = 0;
    try (DirectoryStream<Path> versions = Files.newDirectoryStream(organizationDirectory)) {
      for (Path version : versions) {
        if (!Files.isDirectory(version)) {
          continue;
        }

        try (DirectoryStream<Path> children = Files.newDirectoryStream(version)) {
          for (Path child : children) {
            // Listed and filtered by prefix rather than matched with a DirectoryStream glob. The prefix is a
            // constant here, but the glob form is a trap the applier already documents and there is no reason
            // to reintroduce the shape.
            if (!child.getFileName().toString().startsWith(TEMPORARY_PREFIX)
                || Files.getLastModifiedTime(child).toInstant().isAfter(cutoff)) {
              continue;
            }

            if (Files.deleteIfExists(child)) {
              removed++;
            }
          }
        }
      }
    } catch (IOException e) {
      LOG.log(System.Logger.Level.DEBUG,
              "Unable to sweep orphaned temporary documents under [" + organizationDirectory + "]", e);
    }

    if (removed > 0) {
      LOG.log(System.Logger.Level.INFO, "Removed [{0}] orphaned temporary documents under [{1}]", removed,
              organizationDirectory);
    }
  }
}
```

`StandardOpenOption.SYNC` on the temp write plus `ATOMIC_MOVE` is what §7.4 requires: the document either does not exist or is complete, never partial. `REPLACE_EXISTING` alongside `ATOMIC_MOVE` matters — a resent version must overwrite a corrupt document in place.

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.BriefStoreTest`

Expected: PASS, 12 tests. Note what the suite can and cannot prove: no unit test can force a crash between the `SYNC` write and the `ATOMIC_MOVE`, so real atomicity rests on code inspection. What the tests *do* pin down is every branch `latest()` uses to survive a crash — incomplete directory, unparseable document, wrong organization, wrong version, leftover temp file — plus numeric version ordering.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add the immutable on-disk Brief store with atomic writes"
```

---

### Task 5: The Agency client and the fake Agency

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/agency/TokenSupplier.java`
- Create: `src/main/java/dev/theagencyhq/handler/agency/ConfigTokenSupplier.java`
- Create: `src/main/java/dev/theagencyhq/handler/agency/CurrentVersion.java`
- Create: `src/main/java/dev/theagencyhq/handler/agency/BriefingRequest.java`
- Create: `src/main/java/dev/theagencyhq/handler/agency/BriefingResult.java`
- Create: `src/main/java/dev/theagencyhq/handler/agency/AgencyClient.java`
- Create: `src/test/java/dev/theagencyhq/handler/tests/FakeAgency.java`

`BriefingResponse` and the `exports dev.theagencyhq.handler.agency;` line already landed in Task 3 — do not recreate them.
- Test: `src/test/java/dev/theagencyhq/handler/tests/AgencyClientTest.java`

**Interfaces:**
- Consumes: `Brief` (Task 3), `HandlerConfig` (Task 2).
- Produces:
  - `TokenSupplier` — `String bearerToken()`.
  - `ConfigTokenSupplier(HandlerConfig config)` implementing it.
  - `CurrentVersion(String organizationId, int version, String checksum)` — `@JSON` record.
  - `BriefingRequest(List<CurrentVersion> currentVersions)` — `@JSON` record with `byte[] toJSONBytes()`.
  - `BriefingResult` — sealed interface, records `Updated(List<String> organizationIds, List<Brief> briefs)`, `NotModified()`, `Forbidden()`, `Failed(String reason, boolean authenticationFailure)`.
  - `AgencyClient(String theAgencyURL, TokenSupplier tokens)` with `BriefingResult briefing(List<CurrentVersion> currentVersions)`.
- Produces for tests: `FakeAgency` — `int start()`, `void close()`, `void script(int status, String body)`, `List<String> requestBodies()`, `List<String> authorizationHeaders()`, `String url()`.

**Implementation note — a deliberate extension of §7.2.** The spec's `Failed` record carries only `String reason`, but §4 requires a `401` to log at ERROR while a `5xx` or timeout logs at WARNING. Distinguishing them from a reason string would mean sniffing text, so `Failed` gains a second component, `boolean authenticationFailure`. Everything else in the sealed hierarchy matches §7.2 exactly. Flag this in review if unwanted — the fallback is for `AgencyClient` to log the ERROR itself and for `ReceiveThread` to log nothing.

Read spec §4, §7.1, and §7.2 before starting.

- [ ] **Step 1: Write the fake Agency**

Create `src/test/java/dev/theagencyhq/handler/tests/FakeAgency.java`. This is real HTTP with real status codes and real auth headers — it is reused by Tasks 12 and 15, so get it right once:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.lattejava.http;

/**
 * A scriptable stand-in for The Agency, backed by a real HTTP server on an ephemeral port.
 *
 * @author Brian Pontarelli
 */
public class FakeAgency implements Closeable {
  private final List<String> authorizationHeaders = Collections.synchronizedList(new ArrayList<>());
  private final List<String> contentTypes = Collections.synchronizedList(new ArrayList<>());
  private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
  private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
  private final Queue<Scripted> scripted = new ConcurrentLinkedQueue<>();
  private int port;
  private HTTPServer server;

  public List<String> authorizationHeaders() {
    return List.copyOf(authorizationHeaders);
  }

  @Override
  public void close() {
    if (server != null) {
      server.close();
      server = null;
    }
  }

  public List<String> contentTypes() {
    return List.copyOf(contentTypes);
  }

  public List<String> paths() {
    return List.copyOf(paths);
  }

  public List<String> requestBodies() {
    return List.copyOf(requestBodies);
  }

  /**
   * Queues one response. Responses are consumed in order; when the queue empties, every further request gets a 500.
   *
   * @param status The HTTP status to return.
   * @param body   The response body, ignored for statuses that carry none.
   */
  public void script(int status, String body) {
    scripted.add(new Scripted(status, body));
  }

  public int start() {
    HTTPHandler handler = (req, res) -> {
      paths.add(req.getPath());
      String authorization = req.getHeader("Authorization");
      authorizationHeaders.add(authorization == null ? "" : authorization);
      String contentType = req.getHeader("Content-Type");
      contentTypes.add(contentType == null ? "" : contentType);
      requestBodies.add(req.hasBody() ? new String(req.getBodyBytes(), StandardCharsets.UTF_8) : "");

      Scripted next = scripted.poll();
      if (next == null) {
        res.setStatus(500);
        return;
      }

      res.setStatus(next.status());
      if (next.status() == 200 && next.body() != null && !next.body().isEmpty()) {
        byte[] bytes = next.body().getBytes(StandardCharsets.UTF_8);
        res.setContentLength(bytes.length);
        res.getOutputStream().write(bytes);
      }
    };

    server = new HTTPServer().withHandler(handler).withListener(new HTTPListenerConfiguration(0));
    server.start();
    port = server.getActualPort();
    return port;
  }

  public String url() {
    return "http://localhost:" + port;
  }

  private record Scripted(int status, String body) {
  }
}
```

- [ ] **Step 2: Write the failing `AgencyClient` test**

Create `src/test/java/dev/theagencyhq/handler/tests/AgencyClientTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.agency.BriefingResult;
import dev.theagencyhq.handler.agency.CurrentVersion;

public class AgencyClientTest {
  private FakeAgency agency;

  @Test
  public void connectionRefusedIsAFailure() throws IOException {
    int closed;
    try (ServerSocket socket = new ServerSocket(0)) {
      closed = socket.getLocalPort();
    }

    BriefingResult result = new AgencyClient("http://localhost:" + closed, () -> "t").briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Failed, "Expected Failed but got " + result);
    Assert.assertFalse(((BriefingResult.Failed) result).authenticationFailure());
  }

  @Test
  public void forbiddenIsItsOwnResult() {
    agency.script(403, "");

    Assert.assertTrue(client().briefing(List.of()) instanceof BriefingResult.Forbidden);
  }

  @Test
  public void malformedResponseBodyIsAFailureNotAnException() {
    // The only branch in this class doing real exception conversion. If it regressed, a malformed response would
    // throw out of briefing(), out of the receive cycle, and end the interval loop for the life of the process.
    agency.script(200, "not json at all");

    BriefingResult result = client().briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Failed, "Expected Failed but got " + result);
    Assert.assertFalse(((BriefingResult.Failed) result).authenticationFailure());
  }

  @Test
  public void notModifiedIsItsOwnResult() {
    agency.script(304, "");

    Assert.assertTrue(client().briefing(List.of()) instanceof BriefingResult.NotModified);
  }

  @Test
  public void requestCarriesTheBearerTokenPathAndCurrentVersions() throws IOException {
    agency.script(200, Files.readString(Path.of("src/test/resources/agency/briefing-updated.json")));

    client().briefing(List.of(new CurrentVersion("42", 73, "opaque-42-73")));

    Assert.assertEquals(agency.authorizationHeaders(), List.of("Bearer test-token"));
    Assert.assertEquals(agency.contentTypes(), List.of("application/json"));
    Assert.assertEquals(agency.paths(), List.of("/api/v1/briefing"));
    String body = agency.requestBodies().getFirst();
    Assert.assertTrue(body.contains("\"organizationId\":\"42\""), "Body was: " + body);
    Assert.assertTrue(body.contains("\"version\":73"), "Body was: " + body);
    Assert.assertTrue(body.contains("\"checksum\":\"opaque-42-73\""), "Body was: " + body);
  }

  @BeforeMethod
  public void setUp() {
    agency = new FakeAgency();
    agency.start();
  }

  @AfterMethod
  public void tearDown() {
    agency.close();
  }

  @Test
  public void serverErrorIsAFailureThatIsNotAnAuthenticationFailure() {
    agency.script(500, "");

    BriefingResult result = client().briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Failed, "Expected Failed but got " + result);
    Assert.assertFalse(((BriefingResult.Failed) result).authenticationFailure());
  }

  @Test
  public void unauthorizedIsAFailureFlaggedAsAuthentication() {
    agency.script(401, "");

    BriefingResult result = client().briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Failed, "Expected Failed but got " + result);
    Assert.assertTrue(((BriefingResult.Failed) result).authenticationFailure());
  }

  @Test
  public void updatedCarriesTheEntitledSetAndTheBriefs() throws IOException {
    agency.script(200, Files.readString(Path.of("src/test/resources/agency/briefing-updated.json")));

    BriefingResult result = client().briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Updated, "Expected Updated but got " + result);
    BriefingResult.Updated updated = (BriefingResult.Updated) result;
    Assert.assertEquals(updated.organizationIds(), List.of("42", "43"));
    Assert.assertEquals(updated.briefs().size(), 2);
    Assert.assertEquals(updated.briefs().getFirst().organization().name(), "Acme2");
    Assert.assertEquals(updated.briefs().getFirst().version(), 73);

    // The raw capture must survive the trip through the client untouched
    String wire = Files.readString(Path.of("src/test/resources/agency/briefing-updated.json"));
    Assert.assertTrue(wire.contains(updated.briefs().getFirst().raw()));
  }

  private AgencyClient client() {
    return new AgencyClient(agency.url(), () -> "test-token");
  }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.AgencyClientTest`

Expected: FAIL at compilation — `package dev.theagencyhq.handler.agency does not exist`.

- [ ] **Step 4: Implement the token supplier and the wire records**

Create `src/main/java/dev/theagencyhq/handler/agency/TokenSupplier.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

/**
 * Supplies the bearer token for Agency requests. The OAuth device flow lands behind this interface with no change to
 * {@link AgencyClient}.
 *
 * @author Brian Pontarelli
 */
public interface TokenSupplier {
  String bearerToken();
}
```

Create `src/main/java/dev/theagencyhq/handler/agency/ConfigTokenSupplier.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;

import dev.theagencyhq.handler.config.HandlerConfig;

/**
 * Reads the static bearer token out of {@code handler.json}.
 *
 * @author Brian Pontarelli
 */
public class ConfigTokenSupplier implements TokenSupplier {
  private final HandlerConfig config;

  public ConfigTokenSupplier(HandlerConfig config) {
    this.config = config;
  }

  @Override
  public String bearerToken() {
    return config.accessToken();
  }
}
```

Create `src/main/java/dev/theagencyhq/handler/agency/CurrentVersion.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module org.lattejava.json;

/**
 * One entry in the {@code currentVersions} array the Handler sends to The Agency. The checksum is opaque — it is echoed
 * back exactly as it was received and never computed locally.
 *
 * @author Brian Pontarelli
 */
@JSON
public record CurrentVersion(String organizationId, int version, String checksum) {
}
```

Create `src/main/java/dev/theagencyhq/handler/agency/BriefingRequest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.agency.internal.BriefingRequestJSON;

/**
 * The {@code POST /api/v1/briefing} request body.
 *
 * @author Brian Pontarelli
 */
@JSON
public record BriefingRequest(List<CurrentVersion> currentVersions) {
  public BriefingRequest {
    currentVersions = currentVersions == null ? List.of() : currentVersions;
  }

  public byte[] toJSONBytes() {
    return BriefingRequestJSON.toJSONBytes(this);
  }
}
```

Create `src/main/java/dev/theagencyhq/handler/agency/BriefingResult.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;

import dev.theagencyhq.handler.brief.Brief;

/**
 * The outcome of one briefing request, sealed so no caller can forget a case.
 *
 * @author Brian Pontarelli
 */
public sealed interface BriefingResult {
  record Failed(String reason, boolean authenticationFailure) implements BriefingResult {
  }

  record Forbidden() implements BriefingResult {
  }

  record NotModified() implements BriefingResult {
  }

  record Updated(List<String> organizationIds, List<Brief> briefs) implements BriefingResult {
  }
}
```

- [ ] **Step 5: Implement `AgencyClient`**

Create `src/main/java/dev/theagencyhq/handler/agency/AgencyClient.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module java.net.http;

/**
 * The Agency API client. Every network and protocol failure is converted into a {@link BriefingResult} — this class
 * never throws, because an unavailable Agency must never stop the Handler from distributing what it already has.
 *
 * @author Brian Pontarelli
 */
public class AgencyClient {
  public static final String BRIEFING_PATH = "/api/v1/briefing";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient httpClient;
  private final String theAgencyURL;
  private final TokenSupplier tokens;

  public AgencyClient(String theAgencyURL, TokenSupplier tokens) {
    this.theAgencyURL = theAgencyURL;
    this.tokens = tokens;
    this.httpClient = HttpClient.newBuilder()
                                .connectTimeout(CONNECT_TIMEOUT)
                                .version(HttpClient.Version.HTTP_1_1)
                                .build();
  }

  public BriefingResult briefing(List<CurrentVersion> currentVersions) {
    HttpRequest request;
    try {
      request = HttpRequest.newBuilder(URI.create(theAgencyURL + BRIEFING_PATH))
                           .header("Authorization", "Bearer " + tokens.bearerToken())
                           .header("Content-Type", "application/json")
                           .timeout(REQUEST_TIMEOUT)
                           .POST(HttpRequest.BodyPublishers.ofByteArray(new BriefingRequest(currentVersions)
                                                                            .toJSONBytes()))
                           .build();
    } catch (RuntimeException e) {
      return new BriefingResult.Failed("Unable to build the briefing request: " + e.getMessage(), false);
    }

    HttpResponse<byte[]> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new BriefingResult.Failed("The briefing request was interrupted", false);
    } catch (IOException e) {
      return new BriefingResult.Failed("The Agency at [" + theAgencyURL + "] is unreachable: " + e.getMessage(), false);
    }

    return switch (response.statusCode()) {
      case 200 -> parse(response.body());
      case 304 -> new BriefingResult.NotModified();
      case 401 -> new BriefingResult.Failed("The Agency rejected the access token", true);
      case 403 -> new BriefingResult.Forbidden();
      default -> new BriefingResult.Failed("The Agency returned status [" + response.statusCode() + "]", false);
    };
  }

  private BriefingResult parse(byte[] body) {
    try {
      BriefingResponse response = BriefingResponse.fromJSON(body);
      return new BriefingResult.Updated(response.organizationIds(), response.briefs());
    } catch (RuntimeException e) {
      return new BriefingResult.Failed("The Agency returned a malformed briefing response: " + e.getMessage(), false);
    }
  }
}
```

The field is named `httpClient`, not `HTTPClient`: the acronym rule lowercases an acronym entirely when it *starts* an identifier, the same way it produces `jsonProcess()` rather than `JSONProcess()`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.AgencyClientTest`

Expected: PASS, 8 tests. No `module-info.java` change is needed — Task 3 already exported the `agency` package.

- [ ] **Step 7: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add the Agency briefing client with a sealed result type"
```

---

### Task 6: Location discovery

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/location/MissionTypes.java`
- Create: `src/main/java/dev/theagencyhq/handler/location/LocationMarker.java`
- Create: `src/main/java/dev/theagencyhq/handler/location/Location.java`
- Create: `src/main/java/dev/theagencyhq/handler/location/LocationScanner.java`
- Modify: `src/main/java/module-info.java` → add `exports dev.theagencyhq.handler.location;`
- Test: `src/test/java/dev/theagencyhq/handler/tests/MissionTypesTest.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/LocationScannerTest.java`

**Interfaces:**
- Consumes: `HandlerConfig` (Task 2).
- Produces:
  - `MissionTypes.includes(List<String> fileTypes, List<String> locationTypes)` → `boolean`. Assumes both lists are already trimmed and lowercased by their models' compact constructors.
  - `LocationMarker(String version, String organizationId, List<String> missionTypes)` — `@JSON` record with `static LocationMarker fromJSON(byte[] json)` and `int majorVersion()`.
  - `Location(Path root, String organizationId, List<String> missionTypes)`.
  - `LocationScanner(HandlerConfig config)` with `List<Location> scan()`.
  - `LocationScanner.MARKER_FILENAME` = `"agent-location.json"`, `LocationScanner.MAXIMUM_DEPTH` = `25`, `LocationMarker.SUPPORTED_MAJOR_VERSION` = `1`.

**The truth table is `idea.md`'s, verbatim.** §8.2 says "the truth table in `idea.md` becomes a TestNG `@DataProvider` verbatim." That file lives at the repository root — `./idea.md`, under "## Mission Types" — and its 15 rows are transcribed into the `@DataProvider` below in the same order, with a dash meaning "no Mission Types defined." Do not add, drop, or reorder rows; if the predicate disagrees with a row, the predicate is wrong.

The table's own summary of the rule: "When a file from The Agency has a specific Mission Type, the Location must include that Mission Type in its list or have no list, indicating it accepts everything. Matching Mission Type lists is always an `OR` operation."

`idea.md` also states Mission Types are not case-sensitive. The rows use lowercase because `MissionTypes.includes` takes input already normalized by `BriefFile` and `LocationMarker`; case-insensitivity is covered separately by `markerNormalizationMakesMatchingCaseInsensitive`.

- [ ] **Step 1: Write the failing `MissionTypes` test**

Create `src/test/java/dev/theagencyhq/handler/tests/MissionTypesTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.location.LocationMarker;
import dev.theagencyhq.handler.location.MissionTypes;

public class MissionTypesTest {
  @Test
  public void markerNormalizationMakesMatchingCaseInsensitive() {
    LocationMarker marker = new LocationMarker("1.0.0", " 42 ", List.of(" Web ", "LIBRARY"));

    Assert.assertEquals(marker.organizationId(), "42");
    Assert.assertEquals(marker.missionTypes(), List.of("web", "library"));
    Assert.assertTrue(MissionTypes.includes(List.of("web"), marker.missionTypes()));
  }

  @Test(dataProvider = "truthTable")
  public void truthTable(List<String> fileTypes, List<String> locationTypes, boolean expected) {
    Assert.assertEquals(MissionTypes.includes(fileTypes, locationTypes), expected);
  }

  @DataProvider
  public Object[][] truthTable() {
    // idea.md "## Mission Types", all 15 rows in order. A dash in the source table is an empty list here.
    return new Object[][]{
        //  File Mission Type(s)                Location Mission Type(s)                   Include?
        {List.of(),                             List.of(),                                 true},
        {List.of(),                             List.of("web"),                            true},
        {List.of(),                             List.of("web", "library"),                 true},
        {List.of("web"),                        List.of(),                                 true},
        {List.of("web"),                        List.of("web"),                            true},
        {List.of("web"),                        List.of("web", "library"),                 true},
        {List.of("web"),                        List.of("framework"),                      false},
        {List.of("web"),                        List.of("web", "framework"),               true},
        {List.of("web", "library"),             List.of(),                                 true},
        {List.of("web", "library"),             List.of("web"),                            true},
        {List.of("web", "library"),             List.of("web", "library"),                 true},
        {List.of("web", "library"),             List.of("library"),                        true},
        {List.of("web", "library"),             List.of("framework"),                      false},
        {List.of("web", "library"),             List.of("framework", "web"),               true},
        {List.of("web", "library"),             List.of("framework", "library"),           true}
    };
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.MissionTypesTest`

Expected: FAIL at compilation — `package dev.theagencyhq.handler.location does not exist`.

- [ ] **Step 3: Implement `MissionTypes`, `LocationMarker`, and `Location`**

Create `src/main/java/dev/theagencyhq/handler/location/MissionTypes.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.location;

import module java.base;

/**
 * Mission Type filtering. Both lists arrive already trimmed and lowercased from their models' compact constructors, so
 * matching is case-insensitive by construction.
 *
 * @author Brian Pontarelli
 */
public final class MissionTypes {
  private MissionTypes() {
  }

  /**
   * @param fileTypes     The Mission Types the Brief file declares, or empty for "applies everywhere."
   * @param locationTypes The Mission Types the Location declares, or empty for "accepts everything."
   * @return True if the file belongs in the Location.
   */
  public static boolean includes(List<String> fileTypes, List<String> locationTypes) {
    return fileTypes.isEmpty() || locationTypes.isEmpty() || !Collections.disjoint(fileTypes, locationTypes);
  }
}
```

Create `src/main/java/dev/theagencyhq/handler/location/LocationMarker.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.location;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.location.internal.LocationMarkerJSON;

/**
 * The contents of an {@code agent-location.json} marker. {@code version} is the marker file's format version, not the
 * Brief version.
 *
 * @author Brian Pontarelli
 */
@JSON
public record LocationMarker(String version, String organizationId, List<String> missionTypes) {
  public static final int SUPPORTED_MAJOR_VERSION = 1;

  public LocationMarker {
    version = version == null ? "" : version.trim();
    organizationId = organizationId == null ? "" : organizationId.trim();
    missionTypes = missionTypes == null ? List.of()
                                       : missionTypes.stream().map(t -> t.trim().toLowerCase(Locale.ROOT)).toList();
  }

  public static LocationMarker fromJSON(byte[] json) {
    return LocationMarkerJSON.fromJSON(json);
  }

  /**
   * @return The major component of the SemVer format version, or -1 if it is not parseable.
   */
  public int majorVersion() {
    int dot = version.indexOf('.');
    String major = dot < 0 ? version : version.substring(0, dot);
    try {
      return Integer.parseInt(major);
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
```

Create `src/main/java/dev/theagencyhq/handler/location/Location.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.location;

import module java.base;

/**
 * A discovered Location — a directory containing an {@code agent-location.json} marker. A Location owns its whole
 * subtree.
 *
 * @author Brian Pontarelli
 */
public record Location(Path root, String organizationId, List<String> missionTypes) {
  public static Location from(Path root, LocationMarker marker) {
    return new Location(root, marker.organizationId(), marker.missionTypes());
  }
}
```

- [ ] **Step 4: Run the `MissionTypes` test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.MissionTypesTest`

Expected: PASS, 16 tests (15 truth-table rows plus the normalization test).

- [ ] **Step 5: Write the failing `LocationScanner` test**

Create `src/test/java/dev/theagencyhq/handler/tests/LocationScannerTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.location.Location;
import dev.theagencyhq.handler.location.LocationScanner;

public class LocationScannerTest {
  private Path base;

  @Test
  public void badMarkersAreSkippedWithoutFailingTheScan() throws IOException {
    marker("good", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("malformed", "{\"version\":");
    marker("no-org", "{\"version\":\"1.0.0\"}");
    marker("future-format", "{\"version\":\"2.0.0\",\"organizationId\":\"42\"}");

    Assert.assertEquals(roots(scan()), List.of(base.resolve("good")));
  }

  @Test
  public void excludedDirectoryNamesAreNeverEntered() throws IOException {
    marker("keep", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("node_modules/skipped", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("build/skipped", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("output/skipped", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker(".hidden/skipped", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");

    Assert.assertEquals(roots(scan()), List.of(base.resolve("keep")));
  }

  @Test
  public void markerFieldsLandOnTheLocation() throws IOException {
    marker("app", "{\"version\":\"1.0.0\",\"organizationId\":\" 42 \",\"missionTypes\":[\" Web \",\"LIBRARY\"]}");

    Location location = scan().getFirst();

    Assert.assertEquals(location.root(), base.resolve("app"));
    Assert.assertEquals(location.organizationId(), "42");
    Assert.assertEquals(location.missionTypes(), List.of("web", "library"));
  }

  @BeforeMethod
  public void setUp() throws IOException {
    base = Files.createDirectories(Path.of("build/test/scan-" + UUID.randomUUID()).toAbsolutePath());
  }

  @Test
  public void symbolicLinksAreNeverFollowed() throws IOException {
    // The link MUST live under a marker-less directory. Putting it inside a Location makes the test worthless:
    // traversal prunes at the marker and never enumerates the link at all, so the test would pass even with
    // NOFOLLOW_LINKS stripped out entirely.
    Path target = marker("target", "{\"version\":\"1.0.0\",\"organizationId\":\"99\"}");
    Files.createDirectories(base.resolve("plain"));
    Files.createSymbolicLink(base.resolve("plain/link"), target);

    // Following the link would discover the same marker a second time, at base/plain/link
    Assert.assertEquals(roots(scan()), List.of(base.resolve("target")));
  }

  @Test
  public void traversalStopsAtTheDepthCap() throws IOException {
    StringBuilder deep = new StringBuilder("d0");
    for (int i = 1; i <= LocationScanner.MAXIMUM_DEPTH + 2; i++) {
      deep.append("/d").append(i);
    }

    marker(deep.toString(), "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("shallow", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");

    // The deep marker is past the cap and must not be found; the shallow one still is
    Assert.assertEquals(roots(scan()), List.of(base.resolve("shallow")));
  }

  @Test
  public void anUnreadableDirectoryIsSkippedWithoutAbortingTheScan() throws IOException {
    marker("readable", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    Path locked = Files.createDirectories(base.resolve("locked/inner"));
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked.getParent());
    Files.setPosixFilePermissions(locked.getParent(), PosixFilePermissions.fromString("---------"));

    try {
      Assert.assertEquals(roots(scan()), List.of(base.resolve("readable")));
    } finally {
      // Restore, or the temp tree cannot be cleaned up afterwards
      Files.setPosixFilePermissions(locked.getParent(), original);
    }
  }

  @Test
  public void traversalPrunesAtTheFirstMarker() throws IOException {
    marker("outer", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("outer/inner", "{\"version\":\"1.0.0\",\"organizationId\":\"43\"}");

    // A Location owns its whole subtree, so the nested marker is never seen
    Assert.assertEquals(roots(scan()), List.of(base.resolve("outer")));
  }

  private Path marker(String relative, String json) throws IOException {
    Path directory = Files.createDirectories(base.resolve(relative));
    Files.writeString(directory.resolve("agent-location.json"), json);
    return directory;
  }

  private List<Path> roots(List<Location> locations) {
    return locations.stream().map(Location::root).sorted().toList();
  }

  private List<Location> scan() {
    HandlerConfig config = new HandlerConfig(base.toString(), null, null, null, null, 0, 0);
    return new LocationScanner(config).scan();
  }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.LocationScannerTest`

Expected: FAIL at compilation — `cannot find symbol: class LocationScanner`.

- [ ] **Step 7: Implement `LocationScanner`**

Create `src/main/java/dev/theagencyhq/handler/location/LocationScanner.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.location;

import module java.base;

import dev.theagencyhq.handler.config.HandlerConfig;

/**
 * Finds every Location under the configured start directory. Symbolic links are never followed, which also removes any
 * possibility of a traversal cycle.
 *
 * @author Brian Pontarelli
 */
public class LocationScanner {
  public static final String MARKER_FILENAME = "agent-location.json";
  public static final int MAXIMUM_DEPTH = 25;
  private static final System.Logger LOG = System.getLogger(LocationScanner.class.getName());

  private final List<PathMatcher> excludes;
  private final Path startDirectory;

  public LocationScanner(HandlerConfig config) {
    this.startDirectory = config.startDirectoryPath();
    this.excludes = config.excludeDirectories()
                          .stream()
                          .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                          .toList();
  }

  public List<Location> scan() {
    long start = System.nanoTime();
    List<Location> locations = new ArrayList<>();
    scan(startDirectory, 0, locations);
    long milliseconds = (System.nanoTime() - start) / 1_000_000;
    LOG.log(System.Logger.Level.DEBUG, "Scanned for Locations in [{0}]ms and found [{1}]", milliseconds,
            locations.size());

    return List.copyOf(locations);
  }

  private boolean excluded(Path directory) {
    Path name = directory.getFileName();
    for (PathMatcher matcher : excludes) {
      if (matcher.matches(name)) {
        return true;
      }
    }

    return false;
  }

  private Optional<LocationMarker> marker(Path markerFile) {
    LocationMarker marker;
    try {
      marker = LocationMarker.fromJSON(Files.readAllBytes(markerFile));
    } catch (IOException | RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "Unable to parse the Location marker [" + markerFile + "], skipping it", e);
      return Optional.empty();
    }

    if (marker.organizationId().isEmpty()) {
      LOG.log(System.Logger.Level.ERROR, "Location marker [{0}] has no organizationId, skipping it", markerFile);
      return Optional.empty();
    }

    if (marker.majorVersion() != LocationMarker.SUPPORTED_MAJOR_VERSION) {
      LOG.log(System.Logger.Level.ERROR, "Location marker [{0}] has unsupported format version [{1}], skipping it",
              markerFile, marker.version());
      return Optional.empty();
    }

    return Optional.of(marker);
  }

  private void scan(Path directory, int depth, List<Location> locations) {
    if (depth > MAXIMUM_DEPTH) {
      LOG.log(System.Logger.Level.DEBUG, "Depth cap reached at [{0}]", directory);
      return;
    }

    Path markerFile = directory.resolve(MARKER_FILENAME);
    if (Files.isRegularFile(markerFile, LinkOption.NOFOLLOW_LINKS)) {
      marker(markerFile).ifPresent(marker -> locations.add(Location.from(directory, marker)));
      return;     // a Location owns its whole subtree
    }

    try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
      for (Path child : children) {
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || excluded(child)) {
          continue;
        }

        scan(child, depth + 1, locations);
      }
    } catch (IOException e) {
      LOG.log(System.Logger.Level.DEBUG, "Skipping unreadable directory [" + directory + "]", e);
    }
  }
}
```

`Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)` returns false for a symlink to a directory, which is exactly how directory symlinks get skipped — no separate `isSymbolicLink` check is needed. The marker check uses `NOFOLLOW_LINKS` for the same reason, so a symlinked `agent-location.json` is not a marker either.

Private methods are alphabetized (`excluded`, `marker`, `scan`) rather than keeping the two `scan` overloads adjacent — nothing else in this codebase overrides alphabetization for overload adjacency.

- [ ] **Step 8: Export the package and run the tests**

In `src/main/java/module-info.java`, add `exports dev.theagencyhq.handler.location;` in alphabetical position (after `config`).

Run: `latte test --test=dev.theagencyhq.handler.tests.LocationScannerTest`
Then: `latte test`

Expected: PASS. If `symbolicLinksAreNeverFollowed` fails because the filesystem forbids symlink creation, mark that one test `@Test(enabled = false)` with a comment naming the reason — do not weaken the scanner.

- [ ] **Step 9: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add Location discovery with marker pruning and exclusion globs"
```

---

### Task 7: `Manifest`

The manifest is the Handler's crash-recovery mechanism. Its invariant — it always describes a **superset** of what exists on disk, never a subset — is what makes a hard kill mid-apply safe. Every `append` and `clear` must reach disk before returning. Read spec §8.4.

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/apply/Manifest.java`
- Create: `src/main/java/dev/theagencyhq/handler/apply/FileManifest.java`
- Modify: `src/main/java/module-info.java` → add `exports dev.theagencyhq.handler.apply;`
- Test: `src/test/java/dev/theagencyhq/handler/tests/ManifestTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `Manifest` interface — `void append(Entry entry)`, `void clear()`, `List<Entry> entries()`, nested `record Entry(Path path, boolean directory)`.
  - `FileManifest(Path manifestFile)` — reads an existing manifest, or creates one containing only the version line. Throws `Manifest.UnsupportedManifestException` when the format major version is unknown.
  - `Manifest.FILENAME` = `".handler-manifest"`, `Manifest.FORMAT_VERSION` = `"0.1.0"`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/ManifestTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.apply.FileManifest;
import dev.theagencyhq.handler.apply.Manifest;

public class ManifestTest {
  private Path manifestFile;

  @Test
  public void appendIsVisibleOnDiskBeforeItReturns() throws IOException {
    Manifest manifest = new FileManifest(manifestFile);
    manifest.append(new Manifest.Entry(Path.of(".claude"), true));

    // Honest scope: this proves the bytes left the process, not that they reached the platter. Real durability needs
    // SYNC plus force(true), which no black-box test can observe without crash injection — verify those by reading
    // FileManifest.append, not by trusting this test.
    Assert.assertEquals(Files.readAllLines(manifestFile), List.of("0.1.0", ".claude/"));
  }

  @Test
  public void aZeroLengthManifestIsRestartedRatherThanTreatedAsValid() throws IOException {
    Files.writeString(manifestFile, "");

    Manifest manifest = new FileManifest(manifestFile);

    // Indistinguishable from one the Handler never finished creating, so it gets the version line back
    Assert.assertEquals(Files.readAllLines(manifestFile), List.of("0.1.0"));
    Assert.assertEquals(manifest.entries(), List.of());
  }

  @Test
  public void peekHasNoSideEffectsWhatsoever() throws IOException {
    // peek backs the read-only status command, so it must never create or rewrite anything
    Assert.assertEquals(FileManifest.peek(manifestFile), List.of());
    Assert.assertFalse(Files.exists(manifestFile), "peek must not create a missing manifest");

    Files.writeString(manifestFile, "");
    Assert.assertEquals(FileManifest.peek(manifestFile), List.of());
    Assert.assertEquals(Files.size(manifestFile), 0, "peek must not rewrite a zero-length manifest");

    Files.writeString(manifestFile, "0.1.0\n.claude/\n.claude/a.md\n");
    Assert.assertEquals(FileManifest.peek(manifestFile), List.of(new Manifest.Entry(Path.of(".claude"), true),
                                                                new Manifest.Entry(Path.of(".claude/a.md"), false)));
    Assert.assertEquals(Files.readString(manifestFile), "0.1.0\n.claude/\n.claude/a.md\n");
  }

  @Test
  public void clearTruncatesToTheVersionLine() throws IOException {
    Manifest manifest = new FileManifest(manifestFile);
    manifest.append(new Manifest.Entry(Path.of(".claude"), true));
    manifest.append(new Manifest.Entry(Path.of(".claude/a.md"), false));

    manifest.clear();

    Assert.assertEquals(Files.readAllLines(manifestFile), List.of("0.1.0"));
    Assert.assertEquals(manifest.entries(), List.of());
  }

  @Test
  public void missingManifestIsCreatedWithOnlyTheVersionLine() throws IOException {
    Manifest manifest = new FileManifest(manifestFile);

    Assert.assertEquals(Files.readAllLines(manifestFile), List.of("0.1.0"));
    Assert.assertEquals(manifest.entries(), List.of());
  }

  @Test
  public void reverseOrderIsAlwaysASafeTeardownOrder() {
    Manifest manifest = new FileManifest(manifestFile);
    List.of(new Manifest.Entry(Path.of(".claude"), true),
            new Manifest.Entry(Path.of(".claude/skills"), true),
            new Manifest.Entry(Path.of(".claude/skills/one"), true),
            new Manifest.Entry(Path.of(".claude/skills/one/SKILL.md"), false))
        .forEach(manifest::append);

    // Reload from disk rather than reading the in-memory list. Any append-only list trivially preserves insertion
    // order, so asserting against `manifest.entries()` would test the fixture rather than FileManifest's parsing.
    List<Manifest.Entry> reversed = new FileManifest(manifestFile).entries().reversed();

    // A directory never precedes its own contents in reverse order
    for (int i = 0; i < reversed.size(); i++) {
      for (int j = i + 1; j < reversed.size(); j++) {
        Assert.assertFalse(reversed.get(j).path().startsWith(reversed.get(i).path()),
                           "[" + reversed.get(j).path() + "] must be torn down before ["
                               + reversed.get(i).path() + "]");
      }
    }
  }

  @Test
  public void roundTripsThroughAFreshInstance() {
    Manifest manifest = new FileManifest(manifestFile);
    manifest.append(new Manifest.Entry(Path.of(".claude"), true));
    manifest.append(new Manifest.Entry(Path.of(".claude/a.md"), false));

    List<Manifest.Entry> reloaded = new FileManifest(manifestFile).entries();

    Assert.assertEquals(reloaded, List.of(new Manifest.Entry(Path.of(".claude"), true),
                                          new Manifest.Entry(Path.of(".claude/a.md"), false)));
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.ManifestTest`

Expected: FAIL at compilation — `package dev.theagencyhq.handler.apply does not exist`.

- [ ] **Step 3: Implement the interface**

Create `src/main/java/dev/theagencyhq/handler/apply/Manifest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;
import module org.lattejava.version;

/**
 * The record of everything the Handler created inside one Location. Entries are held in creation order, so reverse
 * order is always a safe teardown order — a directory never precedes its own contents in reverse.
 *
 * <p>Every mutation reaches disk before returning. That is what makes a crash mid-apply recoverable: the manifest
 * always describes a superset of what exists, never a subset.
 *
 * @author Brian Pontarelli
 */
public interface Manifest {
  String FILENAME = ".handler-manifest";
  Version FORMAT_VERSION = new Version("0.1.0");

  /**
   * The Location-root directory every in-flight write is staged in. It is a subtree of the Location, so {@code
   * ATOMIC_MOVE} out of it into any planned path is always a same-filesystem rename.
   */
  String STAGING_DIRECTORY = ".handler-tmp";

  void append(Entry entry);

  void clear();

  List<Entry> entries();

  /**
   * Replaces the manifest's contents with the given entries in a single flushed write. Unlike clear-then-append this
   * leaves no window in which the manifest is a subset of what exists on disk.
   *
   * @param entries The entries to retain, in creation order.
   */
  void reset(List<Entry> entries);

  record Entry(Path path, boolean directory) {
    /**
     * @return The manifest line for this entry. Directories carry a trailing slash; files do not.
     */
    public String line() {
      return directory ? path + "/" : path.toString();
    }

    static Entry parse(String line) {
      boolean directory = line.endsWith("/");
      return new Entry(Path.of(directory ? line.substring(0, line.length() - 1) : line), directory);
    }
  }

  class UnsupportedManifestException extends RuntimeException {
    public UnsupportedManifestException(String message) {
      super(message);
    }
  }
}
```

- [ ] **Step 4: Implement `FileManifest`**

Create `src/main/java/dev/theagencyhq/handler/apply/FileManifest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;

/**
 * The on-disk {@code .handler-manifest}. Reads itself on construction, creating the file with only the version line
 * it is absent.
 *
 * @author Brian Pontarelli
 */
public class FileManifest implements Manifest {
  private final List<Entry> entries = new ArrayList<>();
  private final Path manifestFile;

  public FileManifest(Path manifestFile) {
    this.manifestFile = manifestFile;
    // A zero-length manifest is indistinguishable from one the Handler never finished creating - restart it
    if (Files.isRegularFile(manifestFile) && size(manifestFile) > 0) {
      entries.addAll(peek(manifestFile));
    } else {
      rewrite(List.of());
    }
  }

  /**
   * Reads a manifest's entries without creating or modifying anything, so {@code handler status} stays a pure read.
   *
   * @param manifestFile The manifest to read.
   * @return Its entries in creation order, or an empty list if it does not exist or is empty.
   * @throws Manifest.UnsupportedManifestException If the format major version is unknown.
   */
  public static List<Entry> peek(Path manifestFile) {
    if (!Files.isRegularFile(manifestFile)) {
      return List.of();
    }

    List<String> lines;
    try {
      lines = Files.readAllLines(manifestFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read the manifest [" + manifestFile + "]", e);
    }

    if (lines.isEmpty()) {
      return List.of();
    }

    String version = lines.getFirst().trim();
    int dot = version.indexOf('.');
    String major = dot < 0 ? version : version.substring(0, dot);
    if (!major.equals(Integer.toString(SUPPORTED_MAJOR_VERSION))) {
      throw new UnsupportedManifestException("Manifest [" + manifestFile + "] has unsupported format version ["
                                            + version + "]");
    }

    List<Entry> entries = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      if (!line.isBlank()) {
        entries.add(Entry.parse(line.strip()));
      }
    }

    return List.copyOf(entries);
  }

  @Override
  public void append(Entry entry) {
    try (FileChannel channel = FileChannel.open(manifestFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                                                StandardOpenOption.SYNC)) {
      channel.write(ByteBuffer.wrap((entry.line() + "\n").getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to append to the manifest [" + manifestFile + "]", e);
    }

    entries.add(entry);
  }

  @Override
  public void clear() {
    rewrite(List.of());
    entries.clear();
  }

  @Override
  public List<Entry> entries() {
    return List.copyOf(entries);
  }

  private void rewrite(List<Entry> retained) {
    StringBuilder content = new StringBuilder(FORMAT_VERSION).append('\n');
    for (Entry entry : retained) {
      content.append(entry.line()).append('\n');
    }

    try (FileChannel channel = FileChannel.open(manifestFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                                                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {
      channel.write(ByteBuffer.wrap(content.toString().getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to write the manifest [" + manifestFile + "]", e);
    }
  }

  private long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      // Must NOT fall back to 0. A stat failure on an existing file would make the constructor treat a live manifest
      // as zero-length and truncate it, turning the manifest into a subset of what is on disk — the one thing this
      // class exists to prevent. Failing loudly makes the Location report FAILED and retry next cycle instead.
      throw new UncheckedIOException("Unable to read the size of the manifest [" + file + "]", e);
    }
  }
}
```

- [ ] **Step 5: Export the package and run the test**

Add `exports dev.theagencyhq.handler.apply;` to `src/main/java/module-info.java` in alphabetical position — that is **after** `agency`, not before it (`agency` < `apply`, since `g` < `p`).

Run: `latte test --test=dev.theagencyhq.handler.tests.ManifestTest`

Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add the flushed-per-append handler manifest"
```

---

### Task 8: `GitExclude`

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/apply/GitExclude.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/GitExcludeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `GitExclude(Path locationRoot)` with `boolean repository()`, `void add(List<Path> relativePaths)`, `void remove(List<Path> relativePaths)`, `void ensureExcluded(String line)`. Everything lands in `.git/info/exclude`; nothing here reads or writes `.gitignore` (§3.1 item 6). Resolution of the exclude file is lazy and cached for the instance's lifetime, so a `GitExclude` is constructed once per Location per cycle and never invoked on the unchanged fast path.

Read spec §8.5 and §3.1 item 3.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/GitExcludeTest.java`:

```java
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.GitExcludeTest`

Expected: FAIL at compilation — `cannot find symbol: class GitExclude`.

- [ ] **Step 3: Implement `GitExclude`**

Create `src/main/java/dev/theagencyhq/handler/apply/GitExclude.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;

/**
 * Manages the Location's {@code .git/info/exclude} lines — both the Brief's files and the Handler's own bookkeeping
 * names. Every operation is line-exact and idempotent, and lines the Handler did not write are never touched.
 *
 * <p>Nothing here ever touches {@code .gitignore}. That file is committed and owned by the team, and the Handler's
 * exclusions are per-clone facts about a machine that runs it, not something to impose on everyone who checks the
 * repository out.
 *
 * <p>Resolution is lazy and cached for the instance's lifetime, so constructing one per Location per cycle costs
 * on the unchanged fast path.
 *
 * @author Brian Pontarelli
 */
public class GitExclude {
  private static final Duration GIT_TIMEOUT = Duration.ofSeconds(2);
  private static final System.Logger LOG = System.getLogger(GitExclude.class.getName());

  private Optional<Path> excludeFile;
  private final Path locationRoot;

  public GitExclude(Path locationRoot) {
    this.locationRoot = locationRoot;
  }

  public void add(List<Path> relativePaths) {
    Optional<Path> file = resolve();
    if (file.isEmpty() || relativePaths.isEmpty()) {
      return;
    }

    try {
      Files.createDirectories(file.get().getParent());
      List<String> lines = read(file.get());
      Set<String> present = new HashSet<>(lines);
      boolean changed = false;
      for (Path relativePath : relativePaths) {
        String line = relativePath.toString();
        if (present.add(line)) {
          lines.add(line);
          changed = true;
        }
      }

      if (changed) {
        write(file.get(), lines);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to update the git exclude file [" + file.get() + "]", e);
    }
  }

  /**
   * Ensures a single literal line is present in the Location's {@code .git/info/exclude}, creating the file if needed.
   * Does nothing outside a git working tree.
   *
   * <p>Separate from {@link #add} because that renders a {@code Path}, and the Handler's own names include a directory
   * pattern with a trailing slash that a {@code Path} cannot carry.
   *
   * @param line The exact line to ensure.
   */
  public void ensureExcluded(String line) {
    Optional<Path> file = resolve();
    if (file.isEmpty()) {
      return;
    }

    try {
      Files.createDirectories(file.get().getParent());
      List<String> lines = read(file.get());
      if (lines.contains(line)) {
        return;
      }

      lines.add(line);
      write(file.get(), lines);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to update the git exclude file [" + file.get() + "]", e);
    }
  }

  public void remove(List<Path> relativePaths) {
    Optional<Path> file = resolve();
    if (file.isEmpty() || relativePaths.isEmpty() || !Files.isRegularFile(file.get())) {
      return;
    }

    Set<String> doomed = relativePaths.stream().map(Path::toString).collect(Collectors.toSet());
    try {
      List<String> lines = read(file.get());
      if (lines.removeIf(doomed::contains)) {
        write(file.get(), lines);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to update the git exclude file [" + file.get() + "]", e);
    }
  }

  /**
   * @return True if the Location is inside a git working tree.
   */
  public boolean repository() {
    return resolve().isPresent();
  }

  private List<String> read(Path file) throws IOException {
    return Files.isRegularFile(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                                     : new ArrayList<>();
  }

  private Optional<Path> resolve() {
    if (excludeFile != null) {
      return excludeFile;
    }

    excludeFile = Optional.empty();
    Process process = null;
    Path output = null;
    try {
      // The child's output is redirected to a temp file rather than read via process.getInputStream() so that
      // waitFor's timeout genuinely bounds the whole call. Reading the pipe first would block until the child
      // closes stdout, and FileInputStream.read is not interruptible. Locations are applied in sequence, so a
      // hung git would wedge the entire distribute cycle - and with it the thread the Handler joins on shutdown.
      output = Files.createTempFile("handler-git-", ".out");
      process = new ProcessBuilder("git", "rev-parse", "--git-path", "info/exclude")
          .directory(locationRoot.toFile())
          .redirectOutput(output.toFile())
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();

      if (!process.waitFor(GIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        process.destroyForcibly();
        LOG.log(System.Logger.Level.DEBUG, "git timed out for [{0}], treating it as not a repository", locationRoot);
        return excludeFile;
      }

      String resolvedOutput = Files.readString(output).strip();
      if (process.exitValue() != 0 || resolvedOutput.isEmpty()) {
        LOG.log(System.Logger.Level.DEBUG, "[{0}] is not a git working tree", locationRoot);
        return excludeFile;
      }

      Path resolved = Path.of(resolvedOutput);
      excludeFile = Optional.of(resolved.isAbsolute() ? resolved : locationRoot.resolve(resolved));
    } catch (IOException e) {
      LOG.log(System.Logger.Level.DEBUG, "git is unavailable, treating [" + locationRoot + "] as not a repository", e);
    } catch (InterruptedException e) {
      // Consistent with the timeout branch: kill the subprocess and say why, rather than silently caching a
      // permanent "not a repository" with no trace in the log
      if (process != null) {
        process.destroyForcibly();
      }
      LOG.log(System.Logger.Level.DEBUG, "Interrupted resolving git for [{0}], treating it as not a repository",
              locationRoot);
      Thread.currentThread().interrupt();
    } finally {
      if (output != null) {
        try {
          Files.deleteIfExists(output);
        } catch (IOException ignored) {
          // The temp directory's own cleanup, if any, is the last resort - this is not worth failing resolution over
        }
      }
    }

    return excludeFile;
  }

  private void write(Path file, List<String> lines) throws IOException {
    Files.writeString(file, lines.isEmpty() ? "" : String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.GitExcludeTest`

Expected: PASS, 5 tests. These tests shell out to real `git`; if `git` is not on PATH in the environment, only `nonRepositoryIsDetectedAndEveryOperationIsANoOp` can pass — that is a broken environment, not a code problem. Report it rather than stubbing `git`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add git info/exclude line management"
```

---

### Task 9: `BriefPlanner`

Pure in-memory planning, no disk writes. The path validation here is the security boundary — a Brief must never be able to write outside its Location or corrupt the Handler's own bookkeeping. Read spec §8.3.

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/apply/PlannedFile.java`
- Create: `src/main/java/dev/theagencyhq/handler/apply/LocationPlan.java`
- Create: `src/main/java/dev/theagencyhq/handler/apply/BriefPlanner.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/BriefPlannerTest.java`

**Interfaces:**
- Consumes: `Brief`, `BriefFile`, `StoredBrief` (Tasks 3–4), `Location`, `MissionTypes` (Task 6), `Manifest.FILENAME` (Task 7).
- Produces:
  - `PlannedFile(Path relativePath, byte[] content, Set<PosixFilePermission> mode)`.
  - `LocationPlan(List<PlannedFile> files, SequencedSet<Path> directories)` with `static final LocationPlan EMPTY` and `boolean isEmpty()`.
  - `BriefPlanner` with `LocationPlan plan(StoredBrief storedBrief, Location location)`, throwing `BriefPlanner.InvalidPlanException` when any path or mode is invalid — the whole plan fails, never just the offending file.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/BriefPlannerTest.java`:

```java
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.BriefPlannerTest`

Expected: FAIL at compilation — `cannot find symbol: class BriefPlanner`.

- [ ] **Step 3: Implement `PlannedFile` and `LocationPlan`**

Create `src/main/java/dev/theagencyhq/handler/apply/PlannedFile.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;

/**
 * One file the Handler intends to write into a Location, fully decoded and validated.
 *
 * @author Brian Pontarelli
 */
public record PlannedFile(Path relativePath, byte[] content, Set<PosixFilePermission> mode) {
}
```

Create `src/main/java/dev/theagencyhq/handler/apply/LocationPlan.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;

/**
 * What one Location should contain from one Organization. An empty plan is valid and means "nothing" — it drives a pure
 * teardown, which is both the revocation path and what happens when Mission Type filtering excludes everything.
 *
 * @author Brian Pontarelli
 */
public record LocationPlan(List<PlannedFile> files, SequencedSet<Path> directories) {
  public static final LocationPlan EMPTY = new LocationPlan(List.of(), new LinkedHashSet<>());

  public LocationPlan {
    // EMPTY is a shared constant; without this a caller could add to its directories and poison it for the whole JVM
    files = List.copyOf(files);
    directories = Collections.unmodifiableSequencedSet(new LinkedHashSet<>(directories));
  }

  public boolean isEmpty() {
    return files.isEmpty();
  }
}
```

- [ ] **Step 4: Implement `BriefPlanner`**

Create `src/main/java/dev/theagencyhq/handler/apply/BriefPlanner.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;

import dev.theagencyhq.handler.brief.BriefFile;
import dev.theagencyhq.handler.brief.StoredBrief;
import dev.theagencyhq.handler.location.Location;
import dev.theagencyhq.handler.location.MissionTypes;

/**
 * Turns a Brief plus a Location into an in-memory plan. Writes nothing. Any invalid path or mode fails the whole plan
 * — a Brief must never be able to write outside its Location or corrupt the Handler's own bookkeeping, and a
 * partially applied Brief is worse than a skipped one.
 *
 * @author Brian Pontarelli
 */
public class BriefPlanner {
  private static final String GIT_DIRECTORY = ".git";
  private static final String GITIGNORE = ".gitignore";

  public LocationPlan plan(StoredBrief storedBrief, Location location) {
    List<PlannedFile> files = new ArrayList<>();
    Set<Path> planned = new HashSet<>();
    for (BriefFile file : storedBrief.brief().files()) {
      if (!MissionTypes.includes(file.missionTypes(), location.missionTypes())) {
        continue;
      }

      Path relativePath = validate(file.path(), storedBrief);
      if (!planned.add(relativePath)) {
        // `a//b.md` and `a/b.md` normalize identically. Writing both would double-write the file and put two
        // entries in the manifest, so the second teardown pass would try to delete an already-deleted path.
        throw new InvalidPlanException(describe(storedBrief) + " plans the same path twice [" + relativePath + "]");
      }

      try {
        files.add(new PlannedFile(relativePath, file.decoded(), file.posixMode()));
      } catch (IllegalArgumentException e) {
        throw new InvalidPlanException(describe(storedBrief) + " has an invalid file [" + file.path() + "]: "
                                       + e.getMessage(), e);
      }
    }

    files.sort(Comparator.comparing(PlannedFile::relativePath));

    // Shallowest-first so creation order is always valid
    SequencedSet<Path> directories = new LinkedHashSet<>();
    for (PlannedFile file : files) {
      Path parent = file.relativePath().getParent();
      if (parent == null) {
        continue;
      }

      List<Path> ancestors = new ArrayList<>();
      for (Path ancestor = parent; ancestor != null; ancestor = ancestor.getParent()) {
        ancestors.add(ancestor);
      }

      directories.addAll(ancestors.reversed());
    }

    return new LocationPlan(List.copyOf(files), directories);
  }

  private String describe(StoredBrief storedBrief) {
    return "Brief for Organization [" + storedBrief.organizationId() + "] version [" + storedBrief.version() + "]";
  }

  private Path validate(String rawPath, StoredBrief storedBrief) {
    if (rawPath.isEmpty()) {
      throw new InvalidPlanException(describe(storedBrief) + " has a file with an empty path");
    }

    // Checked before Path.of, so a NUL surfaces as InvalidPlanException rather than letting InvalidPathException
    // escape. A newline is the dangerous one: Manifest.append and GitExclude.add are both line-oriented and neither
    // escapes, so an embedded newline injects a standalone line into the manifest. If that line is an absolute path,
    // root.resolve() returns it unchanged and the next teardown deletes an arbitrary file anywhere on the machine.
    for (int i = 0; i < rawPath.length(); i++) {
      char character = rawPath.charAt(i);
      if (character < 0x20 || character == 0x7F) {
        throw new InvalidPlanException(describe(storedBrief) + " has a control character in the file path [" + rawPath + "]");
      }
    }

    Path path = Path.of(rawPath);
    if (path.isAbsolute()) {
      throw new InvalidPlanException(describe(storedBrief) + " has an absolute file path [" + rawPath + "]");
    }

    for (Path segment : path) {
      String name = segment.toString();
      if (name.equals("..") || name.equals(".")) {
        throw new InvalidPlanException(describe(storedBrief) + " has a relative segment in [" + rawPath + "]");
      }

      // Every segment, case-insensitively — not just the first, and not just the whole path. `tools/.git/config`
      // plants a fabricated repository whose core.fsmonitor executes on the next git call, and macOS APFS is
      // case-insensitive by default so `.GIT/hooks/pre-commit` really is `.git/hooks/pre-commit`.
      String lowered = name.toLowerCase(Locale.ROOT);
      switch (lowered) {
        case GIT_DIRECTORY -> throw new InvalidPlanException(describe(storedBrief) + " has a file path inside .git [" + rawPath + "]");
        case Manifest.FILENAME -> throw new InvalidPlanException(describe(storedBrief) + " tries to write the manifest [" + rawPath + "]");
        // Still rejected, but no longer because the Handler writes it - all Handler exclusions live in
        // .git/info/exclude now. .gitignore is committed and owned by the team, there is no merge, and the Handler
        // always wins at a managed path (§3.1 item 1), so a Brief naming it would silently replace rules the team
        // wrote and reviewed. Delivering a .gitignore is a plausible feature, but it needs deciding on its own.
        case GITIGNORE -> throw new InvalidPlanException(describe(storedBrief) + " tries to write .gitignore [" + rawPath + "]");
        // The applier stages every in-flight write here and deletes the whole directory around each apply, so a Brief
        // file planned inside it would be destroyed without explanation
        case Manifest.STAGING_DIRECTORY -> throw new InvalidPlanException(describe(storedBrief) + " tries to write inside the staging directory [" + rawPath + "]");
      }
    }

    Path normalized = path.normalize();
    if (normalized.toString().isEmpty() || normalized.startsWith("..")) {
      throw new InvalidPlanException(describe(storedBrief) + " has a file path that escapes the Location [" + rawPath + "]");
    }

    return normalized;
  }

  public static class InvalidPlanException extends RuntimeException {
    public InvalidPlanException(String message) {
      super(message);
    }

    public InvalidPlanException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.BriefPlannerTest`

Expected: PASS, 15 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add the Brief planner with path validation and mission type filtering"
```

---

### Task 10: `LocationApplier`

The only class that mutates a Location, and the one that has to be crash-safe. Read spec §8.6 in full before writing a line — the step ordering is load-bearing, and the manifest-before-the-thing-it-describes rule is what makes a hard kill recoverable.

**Everything is staged in `.handler-tmp/` at the Location root** — never beside the target file. Four rules, each with a test:

1. **Step 0 deletes the whole directory**, before the conflict and change checks. That is what collects orphans on a cycle where the Location is `UNCHANGED`, and it is why the sweep no longer has to know which files the current Brief names. Do not move it into the write step.
2. **The write step creates it `0700`, uses it, and deletes it in a `finally`.** Its presence has to keep meaning "a write died partway through," so it must not survive a successful apply *or* a failed one.
3. **Set the POSIX mode on the staged file, before the `ATOMIC_MOVE`.** `rename(2)` carries the inode across, so the file appears at its planned path already correct. Setting it after the move leaves a window at the umask default, which for a Brief declaring `r--------` is a real exposure. It cannot be set at creation either — the file would be read-only before the content is written.
4. **Both Handler names go in `.git/info/exclude`, never `.gitignore`** (§3.1 item 6). `.handler-manifest` is added from `bootstrap()` but only when the manifest is *fresh*, and `.handler-tmp/` from the write step — because `ensureExcluded` resolves the repository via a `git rev-parse` fork, and §8.5 keeps that off the unchanged fast path.

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/apply/ApplyResult.java`
- Create: `src/main/java/dev/theagencyhq/handler/apply/LocationApplier.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/LocationApplierTest.java`

**Interfaces:**
- Consumes: `LocationPlan`, `PlannedFile` (Task 9), `Manifest`, `FileManifest` (Task 7), `GitExclude` (Task 8), `Location` (Task 6).
- Produces:
  - `ApplyResult` enum — `APPLIED`, `FAILED`, `SKIPPED_CONFLICT`, `UNCHANGED`.
  - `LocationApplier` with `ApplyResult apply(Location location, LocationPlan plan, boolean force)`. Never throws — every failure is logged and returned as `FAILED`.

**Implementation note — one mechanical deviation from §8.6.** The change check's byte comparison is specified as `Files.mismatch`, which compares two *files*. The planned content is in memory, so writing it to a temp file just to compare would defeat the purpose of a read-only check. The implementation compares size first, then `Arrays.equals` against `Files.readAllBytes`. Identical semantics, no writes on the unchanged path — which §8.6 requires absolutely.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/LocationApplierTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;
import java.nio.file.Files;

import dev.theagencyhq.handler.apply.*;
import dev.theagencyhq.handler.location.*;

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
    location = new Location(root, "42", List.of());
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.LocationApplierTest`

Expected: FAIL at compilation — `cannot find symbol: class LocationApplier`.

- [ ] **Step 3: Implement `ApplyResult`**

Create `src/main/java/dev/theagencyhq/handler/apply/ApplyResult.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

/**
 * The outcome of applying one plan to one Location.
 *
 * @author Brian Pontarelli
 */
public enum ApplyResult {
  APPLIED,
  FAILED,
  SKIPPED_CONFLICT,
  UNCHANGED
}
```

- [ ] **Step 4: Implement `LocationApplier`**

Create `src/main/java/dev/theagencyhq/handler/apply/LocationApplier.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;

import dev.theagencyhq.handler.location.Location;

/**
 * Applies a plan to a Location through the manifest. The manifest entry for anything is always written before the thing
 * itself exists, so a crash can leave an entry with no file — harmless, the next teardown cleans it up — but never
 * a file the Handler created and does not know about.
 *
 * @author Brian Pontarelli
 */
public class LocationApplier {
  private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
  private static final System.Logger LOG = System.getLogger(LocationApplier.class.getName());

  /**
   * @param location The Location to make match the plan.
   * @param plan     What the Location should contain. An empty plan is a pure teardown.
   * @param force    True to adopt unmanaged files at planned paths instead of skipping the Location.
   * @return The outcome. This method never throws.
   */
  public ApplyResult apply(Location location, LocationPlan plan, boolean force) {
    Path root = location.root();
    try {
      GitExclude gitExclude = new GitExclude(root);
      Manifest manifest = bootstrap(root, gitExclude);

      List<Path> conflicts = conflicts(root, plan, manifest.entries());
      if (!conflicts.isEmpty() && !force) {
        LOG.log(System.Logger.Level.ERROR,
                "Location [{0}] has unmanaged files at planned paths {1} and was skipped. Run [handler sync --force] to"
                + " adopt them.", root, conflicts);
        return ApplyResult.SKIPPED_CONFLICT;
      }

      if (!changed(root, plan, manifest.entries())) {
        return ApplyResult.UNCHANGED;
      }

      List<Manifest.Entry> retained = teardown(root, manifest, gitExclude);
      // A single flushed write, not clear() followed by re-appending: entries teardown could not remove must
      // survive, or a Handler-created directory the developer has since put a file into becomes permanently
      // unrecorded, and a kill between a clear and the re-append would lose them entirely — the manifest turning
      // into a subset of disk is exactly what reset() exists to prevent.
      manifest.reset(retained);
      write(root, plan, manifest, gitExclude);

      return ApplyResult.APPLIED;
    } catch (Manifest.UnsupportedManifestException e) {
      LOG.log(System.Logger.Level.ERROR, "Location [" + root + "] has an unreadable manifest and was skipped", e);
      return ApplyResult.SKIPPED_CONFLICT;
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "Unable to apply to Location [" + root + "]", e);
      return ApplyResult.FAILED;
    }
  }

  /**
   * Computes what {@link #apply} would do without writing anything at all — not even the manifest bootstrap. This is
   * what {@code handler status} uses, so it must stay a pure read.
   *
   * @param location The Location to inspect.
   * @param plan     What the Location should contain.
   * @return The Location's state.
   */
  public LocationState inspect(Location location, LocationPlan plan) {
    Path root = location.root();
    try {
      List<Manifest.Entry> entries = FileManifest.peek(root.resolve(Manifest.FILENAME));
      if (!conflicts(root, plan, entries).isEmpty()) {
        return LocationState.CONFLICT;
      }

      return changed(root, plan, entries) ? LocationState.CHANGED : LocationState.UNCHANGED;
    } catch (Manifest.UnsupportedManifestException e) {
      return LocationState.CONFLICT;
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.DEBUG, "Unable to inspect Location [" + root + "]", e);
      return LocationState.UNREADABLE;
    }
  }

  private Manifest bootstrap(Path root, GitExclude gitExclude) {
    // Every orphan from a crashed write is inside the staging directory, so one delete collects all of them
    // regardless of which files the current Brief still names. Done here rather than in write() so an Organization
    // whose Locations are all UNCHANGED still cleans up after a crash. It costs one existence check on the fast
    // path and never invokes git, which ensureIgnored below would.
    Path staging = root.resolve(Manifest.STAGING_DIRECTORY);
    try {
      deleteRecursively(staging);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to remove the stale staging directory [" + staging + "]", e);
    }

    Path manifestFile = root.resolve(Manifest.FILENAME);
    boolean fresh = !Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS);
    Manifest manifest = new FileManifest(manifestFile);
    if (fresh) {
      // info/exclude, never .gitignore. The manifest is a per-clone fact about a machine running the Handler, so
      // recording it in a committed file would impose it on everyone who checks the repository out - and would leave
      // an uncommittable diff in any clone the developer cannot push to. Only on a fresh manifest, because
      // ensureExcluded resolves the repository through a git fork that must stay off the unchanged fast path.
      gitExclude.ensureExcluded(Manifest.FILENAME);
    }

    return manifest;
  }

  private boolean changed(Path root, LocationPlan plan, List<Manifest.Entry> entries) {
    Set<Path> manifestFiles = entries.stream()
                                     .filter(entry -> !entry.directory())
                                     .map(Manifest.Entry::path)
                                     .collect(Collectors.toSet());
    Set<Path> plannedFiles = plan.files().stream().map(PlannedFile::relativePath).collect(Collectors.toSet());
    if (!manifestFiles.equals(plannedFiles)) {
      return true;
    }

    for (Manifest.Entry entry : entries) {
      if (!Files.exists(root.resolve(entry.path()), LinkOption.NOFOLLOW_LINKS)) {
        return true;
      }
    }

    for (Path directory : plan.directories()) {
      if (!Files.isDirectory(root.resolve(directory), LinkOption.NOFOLLOW_LINKS)) {
        return true;
      }
    }

    for (PlannedFile file : plan.files()) {
      Path target = root.resolve(file.relativePath());
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        return true;      // a symlink or anything else standing in for the file counts as a difference
      }

      try {
        if (Files.size(target) != file.content().length) {
          return true;
        }

        if (!Files.getPosixFilePermissions(target).equals(file.mode())) {
          return true;
        }

        if (!Arrays.equals(Files.readAllBytes(target), file.content())) {
          return true;
        }
      } catch (IOException e) {
        return true;      // unreadable is a difference
      }
    }

    // Modification time plays no part
    return false;
  }

  private void clearReadOnly(Path path) {
    try {
      Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(path,
                                                                                        LinkOption.NOFOLLOW_LINKS));
      if (permissions.add(PosixFilePermission.OWNER_WRITE)) {
        Files.setPosixFilePermissions(path, permissions);
      }
    } catch (IOException e) {
      LOG.log(System.Logger.Level.DEBUG, "Unable to clear read-only on [" + path + "]", e);
    }
  }

  private List<Path> conflicts(Path root, LocationPlan plan, List<Manifest.Entry> entries) {
    Set<Path> managed = entries.stream().map(Manifest.Entry::path).collect(Collectors.toSet());
    List<Path> conflicts = new ArrayList<>();

    for (Path directory : plan.directories()) {
      Path target = root.resolve(directory);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        conflicts.add(directory);       // planned directory exists as a file
      }
    }

    for (PlannedFile file : plan.files()) {
      Path target = root.resolve(file.relativePath());
      if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }

      if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || !managed.contains(file.relativePath())) {
        conflicts.add(file.relativePath());
      }
    }

    conflicts.sort(Comparator.naturalOrder());
    return conflicts;
  }

  private void deleteRecursively(Path path) throws IOException {
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      try (Stream<Path> paths = Files.walk(path)) {
        for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
          clearReadOnly(child);
          Files.deleteIfExists(child);
        }
      }
    } else {
      clearReadOnly(path);
      Files.deleteIfExists(path);
    }
  }

  private List<Manifest.Entry> teardown(Path root, Manifest manifest, GitExclude gitExclude) {
    List<Manifest.Entry> reversed = manifest.entries().reversed();
    gitExclude.remove(reversed.stream().map(Manifest.Entry::path).toList());

    List<Manifest.Entry> retained = new ArrayList<>();
    for (Manifest.Entry entry : reversed) {
      Path target = root.resolve(entry.path());
      try {
        if (entry.directory()) {
          if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            continue;
          }

          clearReadOnly(target);
          try (DirectoryStream<Path> children = Files.newDirectoryStream(target)) {
            if (children.iterator().hasNext()) {
              LOG.log(System.Logger.Level.DEBUG, "Leaving non-empty directory [{0}] in place", target);
              retained.add(entry);
              continue;
            }
          }

          Files.delete(target);
        } else if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
          // A managed file entry now sitting on a directory is exactly what --force exists to adopt. Deleting it
          // here would throw DirectoryNotEmptyException and strand the Location at FAILED forever; leave it for
          // write()'s deleteRecursively to replace, and keep the manifest honest about it until then.
          LOG.log(System.Logger.Level.DEBUG, "Leaving directory [{0}] at a managed file path in place", target);
          retained.add(entry);
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          clearReadOnly(target);
          Files.delete(target);
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Unable to tear down [" + target + "]", e);
      }
    }

    return retained.reversed();      // restore creation order - the loop above walked in reverse
  }

  private void write(Path root, LocationPlan plan, Manifest manifest, GitExclude gitExclude) {
    // One read-modify-write for the whole Brief instead of one per file - O(N) bytes instead of O(N^2)
    gitExclude.add(plan.files().stream().map(PlannedFile::relativePath).toList());

    Path staging = root.resolve(Manifest.STAGING_DIRECTORY);
    try {
      // 0700 so an orphan is unreachable by any other user regardless of the mode it carries. Not recorded in the
      // manifest: it is transient scaffolding rather than delivered content, and it is removed below.
      Files.createDirectory(staging, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to create the staging directory [" + staging + "]", e);
    }

    // Beside .handler-manifest in info/exclude. Done here rather than in bootstrap() so the unchanged fast path
    // never forks git, which resolving the exclude file requires.
    gitExclude.ensureExcluded(Manifest.STAGING_DIRECTORY + "/");

    try {
      writeStaged(root, plan, manifest, staging);
    } finally {
      // Whether the write succeeded or threw, nothing in here is wanted. Leaving it would make the directory's mere
      // presence stop meaning "a write died partway through".
      try {
        deleteRecursively(staging);
      } catch (IOException e) {
        // The next apply's bootstrap sweeps it, so this costs a stale directory rather than the whole cycle
        LOG.log(System.Logger.Level.WARNING, "Unable to remove the staging directory [" + staging + "]", e);
      }
    }
  }

  private void writeStaged(Path root, LocationPlan plan, Manifest manifest, Path staging) {
    Set<Path> created = new HashSet<>();
    for (PlannedFile file : plan.files()) {
      Path relativePath = file.relativePath();
      List<Path> ancestors = new ArrayList<>();
      for (Path ancestor = relativePath.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
        ancestors.add(ancestor);
      }

      for (Path ancestor : ancestors.reversed()) {
        if (!created.add(ancestor)) {
          continue;
        }

        Path target = root.resolve(ancestor);
        try {
          if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            clearReadOnly(target);
            continue;
          }

          if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(target);      // adopted type conflict - force was granted to get here
          }

          // The manifest entry is written before the thing it describes exists
          manifest.append(new Manifest.Entry(ancestor, true));
          Files.createDirectory(target);
          Files.setPosixFilePermissions(target, DIRECTORY_MODE);
        } catch (IOException e) {
          throw new UncheckedIOException("Unable to create directory [" + target + "]", e);
        }
      }

      Path target = root.resolve(relativePath);
      try {
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
          deleteRecursively(target);
        }

        manifest.append(new Manifest.Entry(relativePath, false));

        // Staged in .handler-tmp/ rather than beside the target: an orphan left by a kill between the write and the
        // move never lands in a content directory, where it would be visible to a running Agent, show up untracked
        // in git status, and make the parent non-empty so teardown could never remove it. The staging directory is a
        // subtree of the Location, so this stays a same-filesystem rename and ATOMIC_MOVE always holds.
        //
        // The mode is set on the staged file, before the move. rename(2) carries the inode across, so the file
        // appears at its planned path already correct - there is no post-move window where Brief content sits at
        // the umask default. It cannot be set at creation either: the default mode is r--------, and the write
        // that follows would then be denied.
        Path temporary = staging.resolve(UUID.randomUUID().toString());
        Files.write(temporary, file.content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(temporary, file.mode());
        clearReadOnly(target);      // a previous read-only file must not block the move
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        throw new UncheckedIOException("Unable to write [" + target + "]", e);
      }
    }
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.LocationApplierTest`

Expected: PASS, 11 tests.

`clearReadOnly(target)` before the `ATOMIC_MOVE` deserves attention if `readOnlyManagedFileIsStillReplaced` fails: on POSIX, replacing a file by rename needs write permission on the *parent directory*, not the file, and the Handler creates its own directories `0700` — so this call is belt-and-braces for a pre-existing directory a developer made read-only. Do not remove it.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add the crash-safe Location applier"
```

---

### Task 10a: `IntervalThread`

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/IntervalThread.java`
- Modify: `src/main/java/module-info.java` → add `exports dev.theagencyhq.handler;`
- Test: `src/test/java/dev/theagencyhq/handler/tests/IntervalThreadTest.java`

**Interfaces:**
- Consumes: nothing. This class has no dependency on any other part of the Handler, which is why it is testable with no Agency and no filesystem.
- Produces: `abstract class IntervalThread extends Thread` with `public void nudge()`, `public void shutdown()`, `public void run()`, and two abstract hooks for subclasses: `protected abstract void execute()` and `protected abstract long intervalSeconds()`.

Read spec §9 in full, especially "The receive → distribute nudge."

Numbered `10a` rather than `11` so the existing task numbers and every cross-reference to them stay valid. It must land before Tasks 11 and 12, which both extend it.

**Four things this class has to get right, all of them asserted in the test below:**

1. **`nudgePending` is set before `signal()`.** `Condition.signal()` is edge-triggered — it means nothing to a thread that is not parked. If a nudge lands while `execute()` is running, the signal is discarded and only the flag survives to make the next `awaitNudge()` skip its wait. Reversing these two lines reintroduces the lost wakeup that the flag exists to prevent.
2. **The lock is held only around the wait, never across `execute()`.** Holding it for the whole cycle would make `nudge()` block behind a running distribute, and a `tryLock` that gives up would drop exactly the nudges point 1 preserves.
3. **The wait comes first.** The interval doubles as the initial delay, so a thread started right after the Handler's startup pass does not immediately repeat it.
4. **`shutdown()` never interrupts.** `running` is only tested between cycles, so an in-flight apply always finishes. Interrupting mid-apply is recoverable by §8.6 but there is no reason to steer into the recovery path.

A spurious wakeup from `await` costs one early cycle and needs no handling — every cycle here is idempotent, which is also why no deadline arithmetic is needed to re-wait the remainder.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/IntervalThreadTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.IntervalThread;

/**
 * The nudge, interval, and shutdown mechanics on their own, with no Agency and no filesystem. Every test uses an
 * interval of an hour, so any run at all can only have come from a nudge.
 */
public class IntervalThreadTest {
  private CountingThread thread;

  @Test
  public void aNudgeArrivingDuringARunTriggersOneMoreRun() throws Exception {
    thread = new CountingThread(3600);
    thread.hold();
    thread.start();
    thread.nudge();

    Assert.assertTrue(thread.awaitStarted(5, TimeUnit.SECONDS), "The first run must be in flight");

    // The signal lands with nobody parked. Only nudgePending keeps it from being lost.
    thread.nudge();
    thread.nudge();
    thread.release();

    Assert.assertTrue(thread.await(2, 5, TimeUnit.SECONDS), "A nudge sent mid-run must still cause another run");
    Thread.sleep(500);
    Assert.assertEquals(thread.count(), 2, "Two nudges during one run must coalesce into a single extra run");
  }

  @Test
  public void aNudgeWakesTheThreadWithoutWaitingOutTheInterval() throws Exception {
    thread = new CountingThread(3600);
    thread.start();

    thread.nudge();

    Assert.assertTrue(thread.await(1, 5, TimeUnit.SECONDS), "The nudge must wake the thread immediately");
  }

  @Test
  public void aNudgeSentBeforeTheThreadStartsIsNotLost() throws Exception {
    thread = new CountingThread(3600);

    thread.nudge();
    thread.start();

    Assert.assertTrue(thread.await(1, 5, TimeUnit.SECONDS), "A nudge before start() must survive into the first wait");
  }

  @Test
  public void aThrowingExecuteDoesNotKillTheLoop() throws Exception {
    thread = new CountingThread(3600);
    thread.start();

    thread.throwOnce();
    thread.nudge();
    Assert.assertTrue(thread.await(1, 5, TimeUnit.SECONDS), "The throwing run still counts as a run");

    thread.nudge();
    Assert.assertTrue(thread.await(2, 5, TimeUnit.SECONDS), "A throwing run must not end the loop");
  }

  @Test
  public void shutdownStopsTheThreadWithoutInterruptingIt() throws Exception {
    thread = new CountingThread(3600);
    thread.hold();
    thread.start();
    thread.nudge();
    Assert.assertTrue(thread.awaitStarted(5, TimeUnit.SECONDS), "The run must be in flight");

    thread.shutdown();
    Assert.assertTrue(thread.isAlive(), "shutdown() must not interrupt a run that is in flight");

    thread.release();
    thread.join(5000);

    Assert.assertFalse(thread.isAlive(), "The thread must stop once the in-flight run completes");
    Assert.assertFalse(thread.interrupted, "The in-flight run must never see an interrupt");
  }

  @Test
  public void theFirstRunWaitsOutTheIntervalRatherThanRunningImmediately() throws Exception {
    thread = new CountingThread(3600);

    thread.start();
    Thread.sleep(500);

    Assert.assertEquals(thread.count(), 0, "The interval is also the initial delay");
  }

  @AfterMethod
  public void tearDown() throws Exception {
    if (thread != null) {
      thread.release();
      thread.shutdown();
      thread.join(5000);
      thread = null;
    }
  }

  private static class CountingThread extends IntervalThread {
    volatile boolean interrupted;
    private final AtomicInteger count = new AtomicInteger();
    private volatile CountDownLatch hold = new CountDownLatch(0);
    private final long intervalSeconds;
    private final Object monitor = new Object();
    private final AtomicBoolean shouldThrow = new AtomicBoolean();
    private final CountDownLatch started = new CountDownLatch(1);

    CountingThread(long intervalSeconds) {
      super("interval-test");
      this.intervalSeconds = intervalSeconds;
    }

    boolean await(int target, long timeout, TimeUnit unit) throws InterruptedException {
      long deadline = System.nanoTime() + unit.toNanos(timeout);
      synchronized (monitor) {
        while (count.get() < target) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            return false;
          }

          monitor.wait(Math.max(1, remaining / 1_000_000));
        }
      }

      return true;
    }

    boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
      return started.await(timeout, unit);
    }

    int count() {
      return count.get();
    }

    void hold() {
      hold = new CountDownLatch(1);
    }

    void release() {
      hold.countDown();
    }

    void throwOnce() {
      shouldThrow.set(true);
    }

    @Override
    protected void execute() {
      started.countDown();
      try {
        hold.await();
      } catch (InterruptedException e) {
        interrupted = true;
        Thread.currentThread().interrupt();
      }

      synchronized (monitor) {
        count.incrementAndGet();
        monitor.notifyAll();
      }

      if (shouldThrow.compareAndSet(true, false)) {
        throw new RuntimeException("Simulated cycle failure");
      }
    }

    @Override
    protected long intervalSeconds() {
      return intervalSeconds;
    }
  }
}
```

Every test uses an interval of an hour, so any run at all can only have come from a nudge. `aNudgeArrivingDuringARunTriggersOneMoreRun` is the one that fails if point 1 above is reversed, and `shutdownStopsTheThreadWithoutInterruptingIt` is the one that fails if `shutdown()` starts interrupting.

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.IntervalThreadTest`

Expected: FAIL at compilation — `cannot find symbol: class IntervalThread`.

- [ ] **Step 3: Implement `IntervalThread`**

Create `src/main/java/dev/theagencyhq/handler/IntervalThread.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler;

import module java.base;

/**
 * A service thread that runs {@link #execute()} on a fixed interval and can be woken early by a {@link #nudge()}.
 *
 * <p>The interval is measured from the end of one run to the start of the next, so a slow run never queues up
 * back-to-back runs. Waiting happens before the first run, so the interval doubles as the initial delay and the
 * Handler's startup pass is never immediately repeated.
 *
 * <p>A nudge that arrives while {@link #execute()} is running is recorded in {@code nudgePending} rather than lost.
 * That is the whole reason the flag exists: {@link Condition#signal()} is edge-triggered and means nothing to a thread
 * that is not parked. The flag is set <em>before</em> the signal, so either the parked thread is woken or the next
 * {@link #awaitNudge()} sees the flag and skips the wait entirely.
 *
 * <p>The lock is held only around the wait, never across {@link #execute()}, so {@link #nudge()} never blocks behind a
 * running cycle. Shutdown is a flag plus a signal — never {@link Thread#interrupt()} — so an in-flight run finishes on
 * its own rather than being torn apart partway through.
 *
 * @author Brian Pontarelli
 */
public abstract class IntervalThread extends Thread {
  private static final System.Logger LOG = System.getLogger(IntervalThread.class.getName());

  private final Lock lock = new ReentrantLock();
  private final Condition nudge = lock.newCondition();
  private final AtomicBoolean nudgePending = new AtomicBoolean();
  private volatile boolean running = true;

  protected IntervalThread(String name) {
    super(name);
    setDaemon(true);
  }

  /**
   * Wakes the thread now rather than letting it wait out the rest of its interval. Safe to call from any thread, at any
   * time, including before the thread is started and after it has stopped. The nudge carries no payload and is never a
   * correctness requirement — if it is coalesced away, the next interval run converges normally.
   */
  public void nudge() {
    // Set before signalling. If a run is in flight nobody is parked and the signal is discarded, but the flag survives
    // and the next awaitNudge() skips its wait.
    nudgePending.set(true);
    lock.lock();
    try {
      nudge.signal();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void run() {
    LOG.log(System.Logger.Level.DEBUG, "[{0}] started with an interval of [{1}]s", getName(), intervalSeconds());
    while (running) {
      if (!awaitNudge() || !running) {
        break;
      }

      try {
        execute();
      } catch (Throwable t) {
        // An escape here would end the loop and silently stop the service for the life of the process
        LOG.log(System.Logger.Level.ERROR, "The [" + getName() + "] cycle threw", t);
      }
    }

    LOG.log(System.Logger.Level.DEBUG, "[{0}] stopped", getName());
  }

  /**
   * Stops the thread after the run in flight completes. Never interrupts, so an in-flight apply is always allowed to
   * finish. Callers that need to wait should follow this with {@link Thread#join(long)}.
   */
  public void shutdown() {
    running = false;
    lock.lock();
    try {
      nudge.signal();
    } finally {
      lock.unlock();
    }
  }

  /**
   * One cycle of whatever this service does. Implementations may throw — the loop logs and continues.
   */
  protected abstract void execute();

  /**
   * @return The number of seconds to wait between runs. Read fresh before every wait.
   */
  protected abstract long intervalSeconds();

  /**
   * Waits for a nudge or for the interval to expire, whichever comes first.
   *
   * @return True to run the next cycle, false if the thread was interrupted and must stop.
   */
  private boolean awaitNudge() {
    lock.lock();
    try {
      if (nudgePending.compareAndSet(true, false)) {
        return true;      // A nudge landed while the last cycle was running, so run again immediately
      }

      // A spurious wakeup costs one early cycle, which is harmless - every cycle here is idempotent
      nudge.await(intervalSeconds(), TimeUnit.SECONDS);
      nudgePending.set(false);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      lock.unlock();
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.IntervalThreadTest`

Expected: PASS, 6 tests. Run it two or three times — these are the only genuinely concurrent tests in the suite, and a flaky pass here is a real defect rather than test noise.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add IntervalThread, the nudgeable interval loop behind both services"
```

---

### Task 11: `ReceiveThread`

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/ReceiveThread.java`
- Modify: `src/main/java/module-info.java` → add `exports dev.theagencyhq.handler;`
- Test: `src/test/java/dev/theagencyhq/handler/tests/ReceiveThreadTest.java`

**Interfaces:**
- Consumes: `IntervalThread` (Task 10a), `AgencyClient`, `BriefingResult`, `CurrentVersion` (Task 5), `BriefStore`, `Brief`, `BriefFile` (Tasks 3–4), `HandlerConfig` (Task 2).
- Produces: `ReceiveThread(HandlerConfig config, AgencyClient agency, BriefStore store, DistributeThread distributeThread) extends IntervalThread` with `public boolean receive()` returning whether this cycle changed the store, and `public void execute()` which is `if (receive()) distributeThread.nudge()`. Neither throws — every failure is logged and the store is left authoritative.

Read spec §4, §7.5, and §9's nudge subsection.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/ReceiveThreadTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.ReceiveThread;
import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.brief.Brief;
import dev.theagencyhq.handler.brief.FileBriefStore;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.location.LocationScanner;

public class ReceiveThreadTest {
  private FakeAgency agency;
  private AtomicInteger nudges;
  private ReceiveThread receiveThread;
  private FileBriefStore store;
  private Path storeRoot;

  @Test
  public void aSecondForbiddenCycleSendsNoFurtherNudge() {
    store.store(brief("42", 1, "x", sha256("x")));
    agency.script(403, "");
    agency.script(403, "");

    receive();
    receive();

    Assert.assertTrue(store.revoked("42"));
    Assert.assertEquals(nudges.get(), 1, "A prolonged 403 must not re-signal every cycle");
  }

  @Test
  public void checksumMismatchStoresNothingAndLeavesThePreviousVersionLive() {
    store.store(brief("42", 1, "content", sha256("content")));
    agency.script(200, response("42", 2, "tampered", sha256("something else")));

    receive();

    Assert.assertEquals(store.latest("42").orElseThrow().version(), 1);
    Assert.assertEquals(nudges.get(), 0, "Nothing changed in the store, so no nudge");
  }

  @Test
  public void currentVersionsAreSentFromTheStore() {
    store.store(brief("42", 73, "x", sha256("x")));
    agency.script(304, "");

    receive();

    String body = agency.requestBodies().getFirst();
    Assert.assertTrue(body.contains("\"organizationId\":\"42\""), "Body was: " + body);
    Assert.assertTrue(body.contains("\"version\":73"), "Body was: " + body);
  }

  @Test
  public void failedRequestLeavesTheStoreAloneAndSendsNoNudge() {
    store.store(brief("42", 1, "x", sha256("x")));
    agency.script(500, "");

    receive();

    Assert.assertEquals(store.latest("42").orElseThrow().version(), 1);
    Assert.assertEquals(nudges.get(), 0);
  }

  @Test
  public void forbiddenRevokesEveryStoredOrganizationAndNudges() {
    store.store(brief("42", 1, "x", sha256("x")));
    store.store(brief("43", 1, "y", sha256("y")));
    agency.script(403, "");

    receive();

    Assert.assertTrue(store.revoked("42"));
    Assert.assertTrue(store.revoked("43"));
    Assert.assertEquals(nudges.get(), 1);
  }

  @Test
  public void forbiddenWithAnEmptyStoreSendsNoNudge() {
    agency.script(403, "");

    receive();

    Assert.assertEquals(nudges.get(), 0);
  }

  @Test
  public void notModifiedSendsNoNudge() {
    agency.script(304, "");

    receive();

    Assert.assertEquals(nudges.get(), 0);
  }

  @Test
  public void organizationAbsentFromTheEntitledSetIsMarkedRevoked() {
    store.store(brief("42", 1, "x", sha256("x")));
    store.store(brief("43", 1, "y", sha256("y")));
    // The response entitles only 42, so 43 has been revoked
    agency.script(200, response("42", 2, "x2", sha256("x2")));

    receive();

    Assert.assertFalse(store.revoked("42"));
    Assert.assertTrue(store.revoked("43"));
    Assert.assertEquals(nudges.get(), 1);
  }

  @BeforeMethod
  public void setUp() throws IOException {
    storeRoot = Files.createDirectories(Path.of("build/test/receive-" + UUID.randomUUID()));
    store = new FileBriefStore(storeRoot);
    nudges = new AtomicInteger();
    agency = new FakeAgency();
    agency.start();

    // Neither thread is started - execute() is called directly, and nudge() is counted rather than delivered
    HandlerConfig config = new HandlerConfig(storeRoot.toString(), null, agency.url(), "token", null, 3600, 3600);
    DistributeThread distributeThread = new DistributeThread(config, store, new LocationScanner(config),
                                                             new BriefPlanner(), new LocationApplier()) {
      @Override
      public void nudge() {
        nudges.incrementAndGet();
      }
    };
    receiveThread = new ReceiveThread(config, new AgencyClient(agency.url(), () -> "token"), store, distributeThread);
  }

  @AfterMethod
  public void tearDown() {
    agency.close();
  }

  @Test
  public void updatedStoresTheBriefAndNudgesExactlyOnce() throws IOException {
    agency.script(200, response("42", 73, "For Claude", sha256("For Claude")));

    receive();

    Assert.assertEquals(store.latest("42").orElseThrow().version(), 73);
    Assert.assertEquals(nudges.get(), 1, "One nudge per receive cycle that changed the store, not one per Brief");
    Assert.assertTrue(Files.readString(storeRoot.resolve("42/73/brief.json")).contains("\"version\":73"));
  }

  private Brief brief(String organizationId, int version, String content, String checksum) {
    return Brief.fromJSON(briefJSON(organizationId, version, content, checksum).getBytes(StandardCharsets.UTF_8));
  }

  private String briefJSON(String organizationId, int version, String content, String checksum) {
    return """
        {"checksum":"opaque","organization":{"id":"%s","name":"Org"},"version":%d,"files":[\
        {"path":".claude/a.md","content":"%s","checksum":"%s"}]}"""
        .formatted(organizationId, version, content, checksum);
  }

  private void receive() {
    // execute(), not receive(): the nudge rule lives in execute() and that is what the interval loop runs
    receiveThread.execute();
  }

  private String response(String organizationId, int version, String content, String checksum) {
    return "{\"organizationIds\":[\"" + organizationId + "\"],\"briefs\":["
           + briefJSON(organizationId, version, content, checksum) + "]}";
  }

  private String sha256(String content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.ReceiveThreadTest`

Expected: FAIL at compilation — `cannot find symbol: class ReceiveThread`.

- [ ] **Step 3: Implement `ReceiveThread`**

Create `src/main/java/dev/theagencyhq/handler/ReceiveThread.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler;

import module java.base;

import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.agency.BriefingResult;
import dev.theagencyhq.handler.agency.CurrentVersion;
import dev.theagencyhq.handler.brief.Brief;
import dev.theagencyhq.handler.brief.BriefFile;
import dev.theagencyhq.handler.brief.BriefStore;
import dev.theagencyhq.handler.config.HandlerConfig;

/**
 * The receive service: ask The Agency what changed, verify it, and store it. Revocation is only <em>marked</em> here —
 * {@link DistributeThread} tears the Locations down and purges, so the Handler never deletes the manifest and Brief it
 * needs in order to clean up.
 *
 * <p>Runs every {@code receiveIntervalSeconds}. The nudge is sent once per cycle, after every Brief has been written,
 * so the distribute that follows sees the whole batch rather than starting against a half-written store.
 *
 * @author Brian Pontarelli
 */
public class ReceiveThread extends IntervalThread {
  private static final System.Logger LOG = System.getLogger(ReceiveThread.class.getName());

  private final AgencyClient agency;
  private final HandlerConfig config;
  private final DistributeThread distributeThread;
  private final BriefStore store;

  public ReceiveThread(HandlerConfig config, AgencyClient agency, BriefStore store,
                       DistributeThread distributeThread) {
    super("handler-receive");
    this.config = config;
    this.agency = agency;
    this.store = store;
    this.distributeThread = distributeThread;
  }

  /**
   * One receive cycle followed by the nudge, which is exactly what the interval loop runs. Public because
   * {@link Handler#receive()} and the tests need the real path rather than a 10-second approximation of it —
   * {@code HandlerConfig} clamps the interval at 10 seconds, so waiting one out is the only alternative.
   */
  @Override
  public void execute() {
    if (receive()) {
      distributeThread.nudge();
    }
  }

  /**
   * Runs one receive cycle without nudging. This is the {@code handler sync} path, where the distribute is the next
   * statement and there is nothing to signal.
   *
   * @return True if this cycle changed the store, which is what gates the nudge.
   */
  public boolean receive() {
    // The contract is that this never throws. AgencyClient already guarantees it for the network side, but every
    // BriefStore call can raise UncheckedIOException on a local disk problem, and an escape here would end the
    // interval loop. IntervalThread guards the loop too; this is the inner layer.
    try {
      return receiveOrThrow();
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "The receive cycle failed against the local store", e);
      return false;
    }
  }

  @Override
  protected long intervalSeconds() {
    return config.receiveIntervalSeconds();
  }

  private boolean apply(BriefingResult.Updated updated) {
    boolean changed = false;
    for (Brief brief : updated.briefs()) {
      if (verify(brief)) {
        store.store(brief);
        changed = true;
      }
    }

    Set<String> revoked = new TreeSet<>(store.organizationIds());
    revoked.removeAll(updated.organizationIds());
    for (String organizationId : revoked) {
      if (!store.revoked(organizationId)) {
        store.markRevoked(organizationId);
        changed = true;
      }
    }

    return changed;
  }

  private boolean receiveOrThrow() {
    List<CurrentVersion> versions = store.allCurrent()
                                         .stream()
                                         .map(stored -> new CurrentVersion(stored.organizationId(), stored.version(),
                                             stored.brief().checksum()))
                                         .toList();

    return switch (agency.briefing(versions)) {
      case BriefingResult.NotModified _ -> {
        LOG.log(System.Logger.Level.DEBUG, "The Agency reports every version current");
        yield false;
      }
      case BriefingResult.Failed failed -> {
        // A 401 needs a human; everything else is degraded-but-recovering. Either way the store stays authoritative.
        System.Logger.Level level = failed.authenticationFailure() ? System.Logger.Level.ERROR
            : System.Logger.Level.WARNING;
        LOG.log(level, "Receive failed: [{0}]", failed.reason());
        yield false;
      }
      case BriefingResult.Forbidden _ -> {
        // Only NEWLY revoked organizations count. A purge can be deferred indefinitely (§8.8), so a prolonged 403
        // would otherwise re-nudge on every single cycle even though nothing changed since the last one.
        Set<String> newlyRevoked = new TreeSet<>();
        for (String organizationId : store.organizationIds()) {
          if (!store.revoked(organizationId)) {
            store.markRevoked(organizationId);
            newlyRevoked.add(organizationId);
          }
        }

        if (!newlyRevoked.isEmpty()) {
          LOG.log(System.Logger.Level.ERROR, "The Agency reports no entitlements; revoked [{0}]", newlyRevoked);
        }

        yield !newlyRevoked.isEmpty();
      }
      case BriefingResult.Updated updated -> apply(updated);
    };
  }

  private boolean verify(Brief brief) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }

    for (BriefFile file : brief.files()) {
      byte[] decoded;
      try {
        decoded = file.decoded();
      } catch (IllegalArgumentException e) {
        LOG.log(System.Logger.Level.ERROR, "Organization [" + brief.organization().id() + "] version ["
            + brief.version() + "] file [" + file.path() + "] is undecodable", e);
        return false;
      }

      String actual = HexFormat.of().formatHex(digest.digest(decoded));
      if (!actual.equals(file.checksum())) {
        LOG.log(System.Logger.Level.ERROR,
            "Checksum mismatch for Organization [{0}] version [{1}] file [{2}]: expected [{3}] but computed [{4}]",
            brief.organization().id(), brief.version(), file.path(), file.checksum(), actual);
        return false;
      }
    }

    return true;
  }
}
```

`digest.digest(...)` resets the instance, so one `MessageDigest` is safely reused across every file in a Brief.

- [ ] **Step 4: Export the root package and run the test**

Add `exports dev.theagencyhq.handler;` to `src/main/java/module-info.java` — first in the exports list, since it sorts before every subpackage.

Run: `latte test --test=dev.theagencyhq.handler.tests.ReceiveThreadTest`

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add the receive task with checksum verification and the distribute nudge"
```

---

### Task 12: `DistributeThread`

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/DistributeThread.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/DistributeThreadTest.java`

**Interfaces:**
- Consumes: `IntervalThread` (Task 10a), `BriefStore` (Task 4), `LocationScanner`, `Location` (Task 6), `BriefPlanner`, `LocationApplier`, `ApplyResult`, `LocationPlan` (Tasks 9–10), `HandlerConfig` (Task 2).
- Produces: `DistributeThread(HandlerConfig config, BriefStore store, LocationScanner scanner, BriefPlanner planner, LocationApplier applier) extends IntervalThread` with `public Summary distribute(boolean force)` and nested `record Summary(int applied, int unchanged, int conflict, int failed)`. `distribute` is public and non-final so tests can subclass it to observe calls. Never throws.

Read spec §8.7 and §8.8, including "Sequentially, not fanned out."

**Cover `--force` explicitly.** It is the only path where the Handler destroys something it did not create, and each behavior is a separate trap: an adopted file must be appended to the manifest, a pre-existing *directory* must not be (§8.6), adoption applies at every Location rather than a selected one (§10), a symlink must be replaced as a link rather than written through (§8.6's symlink obligation), and a manifest file entry now sitting on a non-empty directory must become recoverable rather than conflicting forever.

**Implementation note.** §8.3 says an invalid path "fails the entire plan — the Location is skipped with an ERROR." That is not a *conflict* (nothing unmanaged is in the way), so it is counted as `failed` in the summary. This also defers any pending purge for that Organization, which is the conservative choice.

**Apply Locations in a plain loop.** Do not reach for virtual threads or an `ExecutorService` here — §8.7 records the measurements that rejected it. The one invariant the loop has to preserve is that a Location whose apply throws is caught, logged, **and counted as `FAILED`**. Dropping it instead would leave it out of the deferred-purge set below, and a revoked Organization could then be purged while that Location still holds its files.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/DistributeThreadTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.apply.Manifest;
import dev.theagencyhq.handler.brief.Brief;
import dev.theagencyhq.handler.brief.FileBriefStore;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.location.LocationScanner;

import static org.testng.Assert.*;

public class DistributeThreadTest {
  private Path base;
  private FileBriefStore store;

  @Test
  public void anInvalidPlanFailsOnlyItsOwnLocation() throws IOException {
    // An absolute path will be rejected by the planner (not by the store), which fails the whole plan for that Location
    store.store(brief("42", "/etc/passwd", "evil"));
    assertEquals(store.latest("42").orElseThrow().brief().version(), 1);
    location("broken", "42");

    // Store a good brief
    store.store(brief("43", ".claude/a.md", "alpha"));
    Path healthy = location("healthy", "43");

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.failed(), 1);
    assertEquals(summary.applied(), 1);
    assertEquals(Files.readString(healthy.resolve(".claude/a.md")), "alpha");
  }

  @Test
  public void everyMatchingLocationIsUpdatedIndependently() throws IOException {
    store.store(brief("42", ".claude/a.md", "alpha"));
    Path one = location("one", "42");
    Path two = location("nested/two", "42");

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.applied(), 2);
    assertEquals(Files.readString(one.resolve(".claude/a.md")), "alpha");
    assertEquals(Files.readString(two.resolve(".claude/a.md")), "alpha");
  }

  @Test
  public void forceAdoptsAnUnmanagedFileAtAPlannedPath() throws IOException {
    store.store(brief("42", ".claude/a.md", "alpha"));
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
    store.store(brief("42", ".claude/a.md", "alpha"));
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
    store.store(brief("42", ".claude/a.md", "alpha"));
    Path location = location("app", "42");
    // The developer's own directory. The unmanaged file inside it is what routes this through the adopt path.
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "mine");

    assertEquals(distribute(true), new DistributeThread.Summary(1, 0, 0, 0));

    List<String> manifest = Files.readAllLines(location.resolve(Manifest.FILENAME));
    assertTrue(manifest.contains(".claude/a.md"), "The adopted file is the Handler's now");
    assertFalse(manifest.contains(".claude/"),
                "A directory the developer created must never become a teardown candidate, not even under force");
  }

  @Test
  public void forceReplacesADirectorySittingAtAManagedFilePath() throws IOException {
    store.store(brief("42", ".claude/a.md", "alpha"));
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
    store.store(brief("42", "docs/secret.md", "alpha"));
    Path location = location("app", "42");
    Path outside = Files.createDirectories(base.resolve("outside"));
    Files.writeString(outside.resolve("secret.md"), "not the Handler's");
    Files.createSymbolicLink(location.resolve("docs"), outside);

    assertEquals(distribute(false).conflict(), 1, "A symlink at a planned path is an unmanaged entry");

    DistributeThread.Summary summary = distribute(true);

    assertEquals(summary, new DistributeThread.Summary(1, 0, 0, 0));
    assertFalse(Files.isSymbolicLink(location.resolve("docs")), "The link itself is replaced, never followed");
    assertEquals(Files.readString(location.resolve("docs/secret.md")), "alpha");
    assertEquals(Files.readString(outside.resolve("secret.md")), "not the Handler's",
                 "Adopting a link must never write through it to somewhere outside the Location");
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
    store.store(brief("42", ".claude/a.md", "alpha"));
    for (int i = 0; i < 20; i++) {
      location("app" + i, "42");
    }

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.applied(), 20, "Every Location in the scan must be applied and counted");
  }

  @Test
  public void oneConflictingLocationDoesNotStopTheOthers() throws IOException {
    store.store(brief("42", ".claude/a.md", "alpha"));
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
    store.store(brief("42", ".claude/a.md", "alpha"));
    Path location = location("app", "42");
    distribute(false);
    assertTrue(Files.exists(location.resolve(".claude/a.md")));

    store.markRevoked("42");
    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.applied(), 1);
    assertFalse(Files.exists(location.resolve(".claude")), "Revocation tears the Location down");
    assertFalse(Files.exists(base.resolve("store/42")), "Then the store entry is purged");
    assertFalse(store.latest("42").isPresent());
  }

  @Test
  public void revokedOrganizationSurvivesAnUnreadableStartDirectory() throws IOException {
    // An IOException reading the start directory itself means the scan is incomplete, not "nothing exists" - the
    // same class of hazard as an unreadable manifest, but discovered by LocationScanner instead of LocationApplier.
    if (runningAsRoot()) {
      fail("Running as root bypasses POSIX permission checks, so an unreadable start directory cannot be simulated");
    }

    store.store(brief("42", ".claude/a.md", "alpha"));
    location("app", "42");
    distribute(false);
    store.markRevoked("42");

    Path locationsRoot = base.resolve("locations");
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(locationsRoot);
    Files.setPosixFilePermissions(locationsRoot, PosixFilePermissions.fromString("---------"));
    try {
      DistributeThread.Summary summary = distribute(false);

      assertEquals(summary, new DistributeThread.Summary(0, 0, 0, 0));
      assertTrue(Files.exists(base.resolve("store/42")), "The purge must be deferred while the start directory is unreadable");
    } finally {
      Files.setPosixFilePermissions(locationsRoot, original);
    }
  }

  @Test
  public void revokedOrganizationWithAnUnreadableManifestDefersThePurge() throws IOException {
    store.store(brief("42", ".claude/a.md", "alpha"));
    Path location = location("app", "42");

    // An unknown manifest format means the Handler cannot know what it created there, so it must neither tear down
    // nor purge - the Brief has to survive so a later cycle can retry once a human fixes the manifest.
    Files.writeString(location.resolve(".handler-manifest"), "9.0.0\n.claude/\n");
    store.markRevoked("42");

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary.conflict(), 1);
    assertTrue(Files.exists(base.resolve("store/42")), "The Brief needed for a later teardown must survive a deferred purge");
  }

  @Test
  public void revokedOrganizationWithNothingEverSyncedPurgesImmediately() throws IOException {
    store.store(brief("42", ".claude/a.md", "alpha"));
    Path location = location("app", "42");

    // Unmanaged content the Handler never created. There is no manifest, so there is nothing to tear down and
    // nothing to conflict with - the correct outcome is to leave the developer's file alone and purge the store.
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "unmanaged");
    store.markRevoked("42");

    DistributeThread.Summary summary = distribute(false);

    assertEquals(summary, new DistributeThread.Summary(0, 1, 0, 0));
    assertEquals(Files.readString(location.resolve(".claude/a.md")), "unmanaged");
    assertFalse(Files.exists(base.resolve("store/42")), "Nothing to clean up means the purge can proceed");
    assertFalse(store.latest("42").isPresent());
  }

  @BeforeMethod
  public void setUp() throws IOException {
    base = Files.createDirectories(Path.of("build/test/distribute-" + UUID.randomUUID()).toAbsolutePath());
    store = new FileBriefStore(base.resolve("store"));
    Files.createDirectories(base.resolve("locations"));
  }

  @Test
  public void secondPassOverUnchangedLocationsReportsUnchanged() throws IOException {
    store.store(brief("42", ".claude/a.md", "alpha"));
    location("app", "42");
    distribute(false);

    assertEquals(distribute(false), new DistributeThread.Summary(0, 1, 0, 0));
  }

  private Brief brief(String organizationId, String path, String content) {
    String json = """
        {"checksum":"opaque","organization":{"id":"%s","name":"Org"},"version":%d,"files":[\
        {"path":"%s","content":"%s"}]}""".formatted(organizationId, 1, path, content);
    return Brief.fromJSON(json.getBytes(StandardCharsets.UTF_8));
  }

  private DistributeThread.Summary distribute(boolean force) {
    HandlerConfig config = new HandlerConfig(base.resolve("locations").toString(), null, null, null, null, 0, 0);
    return new DistributeThread(config, store, new LocationScanner(config), new BriefPlanner(), new LocationApplier())
        .distribute(force);
  }

  private void initRepository(Path directory) throws IOException {
    // build/test/ lives inside the Handler's own git working tree, so without its own repository GitExclude would
    // resolve straight to this project's real .git/info/exclude - exactly the trap GitExcludeTest documents and
    // LocationApplierTest guards against with this same helper
    Process process;
    try {
      process = new ProcessBuilder("git", "init", "--quiet", "--template=").directory(directory.toFile()).start();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "git init timed out");
      assertEquals(process.exitValue(), 0, "git init failed");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private Path location(String relative, String organizationId) throws IOException {
    Path directory = Files.createDirectories(base.resolve("locations").resolve(relative));
    Files.writeString(directory.resolve("agent-location.json"),
                      "{\"version\":\"1.0.0\",\"organizationId\":\"" + organizationId + "\"}");
    initRepository(directory);
    return directory;
  }

  private boolean runningAsRoot() throws IOException {
    try {
      Process process = new ProcessBuilder("id", "-u").start();
      String output;
      try (InputStream in = process.getInputStream()) {
        output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
      }

      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "id -u timed out");
      return "0".equals(output);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.DistributeThreadTest`

Expected: FAIL at compilation — `cannot find symbol: class DistributeThread`.

- [ ] **Step 3: Implement `DistributeThread`**

Create `src/main/java/dev/theagencyhq/handler/DistributeThread.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler;

import module java.base;

import dev.theagencyhq.handler.apply.ApplyResult;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.apply.LocationPlan;
import dev.theagencyhq.handler.brief.BriefStore;
import dev.theagencyhq.handler.brief.StoredBrief;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.location.Location;
import dev.theagencyhq.handler.location.LocationScanner;

/**
 * The distribute service: find every Location and make it match the Brief its Organization published. Every Location
 * is independent — an exception applying one is logged and never aborts the others.
 *
 * <p>Runs every {@code distributeIntervalSeconds}, and wakes early whenever {@link ReceiveThread} changed the store.
 *
 * @author Brian Pontarelli
 */
public class DistributeThread extends IntervalThread {
  private static final System.Logger LOG = System.getLogger(DistributeThread.class.getName());

  private final LocationApplier applier;
  private final HandlerConfig config;
  private final BriefPlanner planner;
  private final LocationScanner scanner;
  private final BriefStore store;

  public DistributeThread(HandlerConfig config, BriefStore store, LocationScanner scanner, BriefPlanner planner,
                          LocationApplier applier) {
    super("handler-distribute");
    this.config = config;
    this.store = store;
    this.scanner = scanner;
    this.planner = planner;
    this.applier = applier;
  }

  public Summary distribute(boolean force) {
    List<Location> locations = scanner.scan();
    Map<ApplyResult, Integer> counts = new EnumMap<>(ApplyResult.class);
    Set<String> deferredPurges = new HashSet<>();

    // One Location after another. Measured against a virtual-thread fan-out bounded to 8: the steady-state cycle,
    // where every Location is UNCHANGED, is 40ms serially across 100 Locations, and a version bump that rewrites
    // every file is 7.2s against 4.7s. Only 1.5x, because roughly 95% of a changed cycle is Manifest.append()'s
    // fsync and those serialize on the filesystem journal no matter how many threads issue them. That is not worth
    // a Semaphore, an ExecutorService, and Future collection whose interrupt path has to mark every uncollected
    // Location FAILED to keep a revoked Organization from being purged on the assumption that silence means success.
    for (Location location : locations) {
      ApplyResult result;
      try {
        result = applyTo(location, force);
      } catch (RuntimeException e) {
        // Every Location is independent. This must be recorded rather than skipped: an unrecorded Location is
        // invisible to the deferred-purge set below, so a revoked Organization could be purged while this
        // Location's true state is still unknown.
        LOG.log(System.Logger.Level.ERROR, "Location [" + location.root() + "] failed unexpectedly", e);
        result = ApplyResult.FAILED;
      }

      if (result == null) {
        continue;         // skipped without teardown - it belongs in no bucket
      }

      counts.merge(result, 1, Integer::sum);
      if (result != ApplyResult.APPLIED && result != ApplyResult.UNCHANGED) {
        deferredPurges.add(location.organizationId());
      }
    }

    if (scanner.startDirectoryUnreadable()) {
      // An empty or incomplete scan is indistinguishable from "no Locations exist" for any Organization the scan
      // failed to reach. Purging here would tear down a revoked Organization whose Location is merely unreachable
      // right now, and its files would live on that machine forever once the drive comes back.
      LOG.log(System.Logger.Level.WARNING,
              "Deferring every revocation purge because the start directory could not be fully scanned");
    } else {
      purgeCompletedRevocations(deferredPurges);
    }

    Summary summary = new Summary(counts.getOrDefault(ApplyResult.APPLIED, 0),
                                  counts.getOrDefault(ApplyResult.UNCHANGED, 0),
                                  counts.getOrDefault(ApplyResult.SKIPPED_CONFLICT, 0),
                                  counts.getOrDefault(ApplyResult.FAILED, 0));
    LOG.log(System.Logger.Level.INFO, "applied={0} unchanged={1} conflict={2} failed={3}", summary.applied(),
            summary.unchanged(), summary.conflict(), summary.failed());

    return summary;
  }

  @Override
  protected void execute() {
    distribute(false);
  }

  @Override
  protected long intervalSeconds() {
    return config.distributeIntervalSeconds();
  }

  private ApplyResult applyTo(Location location, boolean force) {
    String organizationId = location.organizationId();
    Optional<StoredBrief> stored = store.latest(organizationId);
    boolean revoked = store.revoked(organizationId);

    if (stored.isEmpty() && !revoked) {
      // Distinct from revocation: there is nothing to tear down and the developer may simply not have access yet
      LOG.log(System.Logger.Level.WARNING, "Location [{0}] names Organization [{1}] which has no Brief; skipping it",
              location.root(), organizationId);
      return null;
    }

    LocationPlan plan;
    if (stored.isPresent() && !revoked) {
      try {
        plan = planner.plan(stored.get(), location);
      } catch (BriefPlanner.InvalidPlanException e) {
        LOG.log(System.Logger.Level.ERROR, "Location [" + location.root() + "] was skipped: " + e.getMessage(), e);
        return ApplyResult.FAILED;
      }
    } else {
      plan = LocationPlan.EMPTY;
    }

    return applier.apply(location, plan, force);
  }

  private void purgeCompletedRevocations(Set<String> deferred) {
    for (String organizationId : store.organizationIds()) {
      if (!store.revoked(organizationId)) {
        continue;
      }

      if (deferred.contains(organizationId)) {
        LOG.log(System.Logger.Level.WARNING,
                "Deferring the purge of revoked Organization [{0}] until every Location tears down cleanly",
                organizationId);
        continue;
      }

      store.purge(organizationId);
    }
  }

  public record Summary(int applied, int unchanged, int conflict, int failed) {
    public boolean clean() {
      return conflict == 0 && failed == 0;
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.DistributeThreadTest`

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add the distribute task with per-Location isolation and revocation purge"
```

---

### Task 13: `Handler` — service threads, startup pass, and shutdown

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/Handler.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/HandlerTest.java`

**Interfaces:**
- Consumes: `ReceiveThread` (Task 11), `DistributeThread` (Task 12), `HandlerConfig` (Task 2), `AgencyClient` (Task 5), `BriefStore` (Task 4).
- Owns no scheduling of its own — that lives in `IntervalThread` (Task 10a). `Handler` only runs the startup pass, starts the two threads in the right order, and joins them on shutdown.
- Produces: `Handler(HandlerConfig config, AgencyClient agency, BriefStore store, DistributeThread distributeThread)` with:
  - `void daemon()` — startup pass, start both threads, block until shutdown.
  - `void daemonAsync()` — the same without blocking.
  - `DistributeThread.Summary syncOnce(boolean force)` — the startup pass and `handler sync`. Nudge is a no-op.
  - `void receiveOnce()` — one receive cycle **with the nudge armed**, exactly what the receive thread's loop runs.
  - `void shutdown()`.

Read spec §9 in full, especially "The receive → distribute nudge."

**Why `receiveOnce()` is public.** The startup pass calls `receive()` directly and sends no nudge (§9 — the distribute is the next statement), so a nudge can only ever originate from the interval loop calling `execute()`. Without a public entry point, testing the nudge would mean waiting out a real interval, and the floor is 10 seconds because `HandlerConfig` clamps there. `receiveOnce()` delegates to the exact method the loop invokes, so the tests exercise the real path in milliseconds rather than a 10-second approximation of it.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/HandlerTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.Handler;
import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.brief.FileBriefStore;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.location.LocationScanner;

public class HandlerTest {
  private FakeAgency agency;
  private Path base;
  private CountingDistributeThread distributeThread;
  private Handler handler;
  private FileBriefStore store;

  @Test
  public void aReceiveThatChangesNothingSendsNoNudge() throws Exception {
    agency.script(304, "");
    handler = handler(3600, 3600);
    distributeThread.start();

    handler.receive();
    Thread.sleep(500);

    Assert.assertEquals(distributeThread.count(), 0, "A 304 must not nudge");
  }

  @Test
  public void aStoredBriefNudgesTheDistributeThreadWithoutWaitingOutTheInterval() throws Exception {
    // Both intervals are an hour, so a distribute can only come from the nudge
    agency.script(200, response("42", 1));
    handler = handler(3600, 3600);
    distributeThread.start();

    handler.receive();

    Assert.assertTrue(distributeThread.await(1, 5, TimeUnit.SECONDS), "The nudge must trigger a distribute immediately");
    Assert.assertEquals(store.latest("42").orElseThrow().version(), 1);
  }

  @Test
  public void aThrowingDistributeDoesNotKillLaterRuns() throws Exception {
    agency.script(200, response("42", 1));
    agency.script(200, response("43", 1));
    handler = handler(3600, 3600);
    distributeThread.start();

    distributeThread.throwOnce();
    handler.receive();
    Assert.assertTrue(distributeThread.await(1, 5, TimeUnit.SECONDS), "The throwing run still counts as a run");

    handler.receive();
    Assert.assertTrue(distributeThread.await(2, 5, TimeUnit.SECONDS),
        "A throwing distribute must not prevent later runs");
  }

  @Test
  public void concurrentNudgesCollapseIntoAtMostOneExtraDistribute() throws Exception {
    agency.script(200, response("42", 1));
    agency.script(200, response("43", 1));
    agency.script(200, response("44", 1));
    handler = handler(3600, 3600);
    distributeThread.start();

    distributeThread.hold();
    handler.receive();
    Assert.assertTrue(distributeThread.awaitStarted(5, TimeUnit.SECONDS), "The first distribute must be in flight");
    handler.receive();
    handler.receive();
    distributeThread.release();

    // One in flight plus at most one coalesced follow-up. Without nudgePending this would be three.
    Assert.assertTrue(distributeThread.await(2, 10, TimeUnit.SECONDS));
    Thread.sleep(500);
    Assert.assertTrue(distributeThread.count() <= 2, "Expected at most 2 distributes, saw " + distributeThread.count());
  }

  @Test
  public void daemonBlocksUntilShutdownReleasesIt() throws Exception {
    agency.script(304, "");
    handler = handler(3600, 3600);

    Thread caller = new Thread(handler::daemon, "daemon-caller");
    caller.start();

    Assert.assertTrue(distributeThread.await(1, 5, TimeUnit.SECONDS), "The startup pass must run");
    Assert.assertTrue(caller.isAlive(), "daemon() must block rather than returning immediately");

    handler.shutdown();
    caller.join(10_000);

    Assert.assertFalse(caller.isAlive(), "shutdown() must release a caller blocked in daemon()");
  }

  @Test
  public void oneReceiveCycleStoringSeveralBriefsSendsOneNudge() throws Exception {
    agency.script(200, "{\"organizationIds\":[\"42\",\"43\",\"44\"],\"briefs\":["
        + brief("42", 1) + "," + brief("43", 1) + "," + brief("44", 1) + "]}");
    handler = handler(3600, 3600);
    distributeThread.start();

    handler.receive();

    Assert.assertTrue(distributeThread.await(1, 5, TimeUnit.SECONDS));
    Thread.sleep(500);
    Assert.assertEquals(distributeThread.count(), 1, "Three Briefs in one cycle must coalesce into one nudge");
  }

  @BeforeMethod
  public void setUp() throws IOException {
    base = Files.createDirectories(Path.of("build/test/handler-" + UUID.randomUUID()).toAbsolutePath());
    Files.createDirectories(base.resolve("locations"));
    store = new FileBriefStore(base.resolve("store"));
    agency = new FakeAgency();
    agency.start();
  }

  @Test
  public void syncOnceRunsReceiveThenDistributeAndSendsNoNudge() throws Exception {
    agency.script(200, response("42", 1));
    handler = handler(3600, 3600);

    handler.receiveAndDistribute(false);

    Assert.assertEquals(store.latest("42").orElseThrow().version(), 1);
    Assert.assertEquals(distributeThread.count(), 1, "syncOnce distributes exactly once - the nudge is a no-op");
  }

  @AfterMethod
  public void tearDown() {
    if (handler != null) {
      handler.shutdown();
      handler = null;
    }

    agency.close();
  }

  private String brief(String organizationId, int version) {
    return """
        {"checksum":"opaque","organization":{"id":"%s","name":"Org"},"version":%d,"files":[]}"""
        .formatted(organizationId, version);
  }

  private Handler handler(int receiveInterval, int distributeInterval) {
    HandlerConfig config = new HandlerConfig(base.resolve("locations").toString(), null, agency.url(), "token", null,
        receiveInterval, distributeInterval);
    distributeThread = new CountingDistributeThread(config, store, new LocationScanner(config), new BriefPlanner(),
        new LocationApplier());
    return new Handler(config, new AgencyClient(config.theAgencyURL(), config::accessToken), store, distributeThread);
  }

  private String response(String organizationId, int version) {
    return "{\"organizationIds\":[\"" + organizationId + "\"],\"briefs\":[" + brief(organizationId, version) + "]}";
  }

  /** Counts distribute calls so the nudge can be observed without adding production indirection. */
  private static class CountingDistributeThread extends DistributeThread {
    private final AtomicInteger count = new AtomicInteger();
    private volatile CountDownLatch hold = new CountDownLatch(0);
    private final Object monitor = new Object();
    private final AtomicBoolean shouldThrow = new AtomicBoolean();
    private final CountDownLatch started = new CountDownLatch(1);

    CountingDistributeThread(HandlerConfig config, FileBriefStore store, LocationScanner scanner, BriefPlanner planner,
                             LocationApplier applier) {
      super(config, store, scanner, planner, applier);
    }

    boolean await(int target, long timeout, TimeUnit unit) throws InterruptedException {
      long deadline = System.nanoTime() + unit.toNanos(timeout);
      synchronized (monitor) {
        while (count.get() < target) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            return false;
          }

          monitor.wait(Math.max(1, remaining / 1_000_000));
        }
      }

      return true;
    }

    boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
      return started.await(timeout, unit);
    }

    int count() {
      return count.get();
    }

    /** Blocks the next {@link #distribute(boolean)} call until {@link #release()} is called. */
    void hold() {
      hold = new CountDownLatch(1);
    }

    void release() {
      hold.countDown();
    }

    /** Makes the next {@link #distribute(boolean)} call throw after counting, to prove the guard survives it. */
    void throwOnce() {
      shouldThrow.set(true);
    }

    @Override
    public Summary distribute(boolean force) {
      started.countDown();
      try {
        hold.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      if (shouldThrow.compareAndSet(true, false)) {
        synchronized (monitor) {
          count.incrementAndGet();
          monitor.notifyAll();
        }

        throw new RuntimeException("Simulated distribute failure");
      }

      Summary summary = super.distribute(force);
      synchronized (monitor) {
        count.incrementAndGet();
        monitor.notifyAll();
      }

      return summary;
    }
  }
}
```

`daemonAsync()` does exactly what `daemon()` does but returns instead of blocking; `daemon()` is `daemonAsync()` followed by awaiting the shutdown latch. It exists so a caller can start the threads without surrendering its own — the CLI's `daemon` subcommand wants the blocking form, but nothing else does.

The nudge tests call `distributeThread.start()` explicitly rather than going through `daemonAsync()`, because `daemonAsync()` runs the startup pass first and that distribute would be counted alongside the nudged one.

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.HandlerTest`

Expected: FAIL at compilation — `cannot find symbol: class Handler`.

- [ ] **Step 3: Implement `Handler`**

Create `src/main/java/dev/theagencyhq/handler/Handler.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler;

import module java.base;

import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.brief.BriefStore;
import dev.theagencyhq.handler.config.HandlerConfig;

/**
 * Owns the two service threads, the startup pass, and process lifecycle. Each thread runs its own interval and wakes
 * itself, so a slow receive can never delay a distribute. The store on disk is the only shared state between them; the
 * nudge carries no payload and is never a correctness requirement.
 *
 * @author Brian Pontarelli
 */
public class Handler {
  private static final System.Logger LOG = System.getLogger(Handler.class.getName());

  private final HandlerConfig config;
  private final DistributeThread distributeThread;
  private final ReceiveThread receiveThread;
  private final CountDownLatch shutdown = new CountDownLatch(1);

  public Handler(HandlerConfig config, AgencyClient agency, BriefStore store, DistributeThread distributeThread) {
    this.config = config;
    this.distributeThread = distributeThread;
    this.receiveThread = new ReceiveThread(config, agency, store, distributeThread);
  }

  /**
   * Runs the startup pass, starts both threads, and blocks until {@link #shutdown()}.
   */
  public void daemon() {
    daemonAsync();
    try {
      shutdown.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Runs the startup pass and starts both threads without blocking.
   */
  public void daemonAsync() {
    receiveAndDistribute(false);

    // Each thread waits before its first run, so its interval is also its initial delay and neither immediately
    // repeats the startup pass. Distribute starts first so a nudge from the very first receive is never dropped.
    distributeThread.start();
    receiveThread.start();
    LOG.log(System.Logger.Level.INFO, "Handler started with receive every [{0}]s and distribute every [{1}]s",
        config.receiveIntervalSeconds(), config.distributeIntervalSeconds());
  }

  /**
   * Runs one receive cycle with the nudge armed. This is exactly what the receive thread's interval loop invokes.
   */
  public void receiveOnce() {
    receiveThread.execute();
  }

  public void shutdown() {
    // Receive first, so it stops feeding nudges before the distribute side is told to stop
    receiveThread.shutdown();
    distributeThread.shutdown();

    try {
      // Give an in-flight apply time to finish; a hard kill mid-apply is still safe by the manifest invariant.
      // join() on a thread that was never started returns immediately, which is the handler-sync path.
      receiveThread.join(10_000);
      distributeThread.join(10_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    shutdown.countDown();
  }

  /**
   * Performs exactly the startup pass — one receive then one distribute — and returns the distribute summary. This
   * is also the implementation of {@code handler sync}, so the daemon's cold start and the one-shot command cannot
   * drift apart. No nudge is sent: the distribute is the next statement.
   *
   * @param force True to adopt conflicting files at every Location.
   * @return The distribute summary.
   */
  public DistributeThread.Summary syncOnce(boolean force) {
    // Receive first: on a machine with an empty store there is nothing to distribute until the Agency has been called
    try {
      receiveThread.receive();
    } catch (Throwable t) {
      LOG.log(System.Logger.Level.ERROR, "The receive step of the startup pass failed", t);
    }

    try {
      return distributeThread.distribute(force);
    } catch (Throwable t) {
      // Unguarded, a purge failure here would escape daemonAsync() before either thread is started, so the daemon
      // would never run at all rather than degrading one cycle
      LOG.log(System.Logger.Level.ERROR, "The distribute step of the startup pass failed", t);
      return new DistributeThread.Summary(0, 0, 0, 1);
    }
  }
}
```

`nudgePending` is what makes a nudge sent mid-cycle safe: `Condition.signal()` is edge-triggered and means nothing to a thread that is not parked, so without the flag that nudge would be lost and its Briefs would wait out a full interval. Because one thread runs the loop, two distributes can never overlap.

- [ ] **Step 4: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.HandlerTest`

Expected: PASS, 4 tests.

If `aStoredBriefNudgesTheDistributeTaskWithoutWaitingOutTheInterval` times out, the nudge is not wired: check that `receiveOnce()` uses `nudgingReceiveTask` and not `oneShotReceiveTask`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add the Handler service threads with the receive to distribute nudge"
```

---

### Task 14: Logging, the CLI, and `Main`

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/apply/LocationState.java`
- Modify: `src/main/java/dev/theagencyhq/handler/apply/LocationApplier.java` (add `inspect`)
- Create: `src/main/java/dev/theagencyhq/handler/log/Logging.java`
- Create: `src/main/java/dev/theagencyhq/handler/cli/HandlerCLI.java`
- Create: `src/main/java/dev/theagencyhq/handler/Main.java`
- Modify: `src/main/java/module-info.java` (add `exports dev.theagencyhq.handler.cli;` and `exports dev.theagencyhq.handler.log;`)
- Test: `src/test/java/dev/theagencyhq/handler/tests/HandlerCLITest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–13.
- Produces:
  - `LocationState` enum — `CHANGED`, `CONFLICT`, `UNCHANGED`, `UNREADABLE`.
  - `LocationApplier.inspect(Location location, LocationPlan plan)` → `LocationState`. **Writes nothing**, which is what keeps `handler status` a pure read.
  - `Logging.configure(HandlerPaths paths)`.
  - `HandlerCLI(HandlerPaths paths, HandlerConfig config, BriefStore store, LocationScanner scanner, BriefPlanner planner, LocationApplier applier, Handler handler, PrintStream out)` with `int run(String... args)` returning the process exit code.
  - `Main.main(String[] args)`.

Read spec §10 and §11.

- [ ] **Step 1: Add `LocationState` and `LocationApplier.inspect`**

Create `src/main/java/dev/theagencyhq/handler/apply/LocationState.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

/**
 * The state of a Location as computed by a read-only inspection.
 *
 * @author Brian Pontarelli
 */
public enum LocationState {
  CHANGED,
  CONFLICT,
  UNCHANGED,
  UNREADABLE
}
```

Add this method to `LocationApplier`, in alphabetical position among the public methods (after `apply`):

```java
  /**
   * Computes what {@link #apply} would do without writing anything at all — not even the manifest bootstrap. This is
   * what {@code handler status} uses, so it must stay a pure read.
   *
   * @param location The Location to inspect.
   * @param plan     What the Location should contain.
   * @return The Location's state.
   */
  public LocationState inspect(Location location, LocationPlan plan) {
    Path root = location.root();
    try {
      List<Manifest.Entry> entries = FileManifest.peek(root.resolve(Manifest.FILENAME));
      if (!conflicts(root, plan, entries).isEmpty()) {
        return LocationState.CONFLICT;
      }

      return changed(root, plan, entries) ? LocationState.CHANGED : LocationState.UNCHANGED;
    } catch (Manifest.UnsupportedManifestException e) {
      return LocationState.CONFLICT;
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.DEBUG, "Unable to inspect Location [" + root + "]", e);
      return LocationState.UNREADABLE;
    }
  }
```

- [ ] **Step 2: Implement `Logging`**

Create `src/main/java/dev/theagencyhq/handler/log/Logging.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.log;

import module java.base;
import module java.logging;

import dev.theagencyhq.handler.config.HandlerPaths;

/**
 * Configures the JUL backend that sits behind {@code System.Logger}: one line per record to stderr so launchd and
 * systemd capture it, plus a size-capped rotating file.
 *
 * @author Brian Pontarelli
 */
public final class Logging {
  public static final int LOG_FILE_COUNT = 3;
  public static final int LOG_FILE_LIMIT = 5 * 1024 * 1024;
  public static final String LEVEL_PROPERTY = "handler.log.level";

  private Logging() {
  }

  /**
   * @param paths The resolved Handler paths, used for the log file location.
   */
  public static void configure(HandlerPaths paths) {
    Logger root = Logger.getLogger("");
    for (java.util.logging.Handler existing : root.getHandlers()) {
      root.removeHandler(existing);
    }

    // Fully qualified for the same reason Handler and FileHandler are below: import module java.base exports
    // java.util.Formatter and import module java.logging exports java.util.logging.Formatter, so the bare name
    // is ambiguous and will not compile
    java.util.logging.Formatter formatter = new OneLineFormatter();

    ConsoleHandler console = new ConsoleHandler();      // ConsoleHandler writes to System.err
    console.setFormatter(formatter);
    console.setLevel(Level.ALL);
    root.addHandler(console);

    try {
      Files.createDirectories(paths.logFile().getParent());
      java.util.logging.FileHandler file = new java.util.logging.FileHandler(paths.logFile().toString(), LOG_FILE_LIMIT,
                                                                            LOG_FILE_COUNT, true);
      file.setFormatter(formatter);
      file.setLevel(Level.ALL);
      root.addHandler(file);
    } catch (IOException e) {
      // stderr logging still works, so this is degraded rather than fatal
      root.log(Level.WARNING, "Unable to open the log file [" + paths.logFile() + "]", e);
    }

    root.setLevel(level());
  }

  private static Level level() {
    String configured = System.getProperty(LEVEL_PROPERTY);
    if (configured == null || configured.isBlank()) {
      return Level.INFO;
    }

    try {
      return Level.parse(configured.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return Level.INFO;
    }
  }

  private static class OneLineFormatter extends java.util.logging.Formatter {
    @Override
    public String format(LogRecord record) {
      StringBuilder line = new StringBuilder(128);
      line.append(DateTimeFormatter.ISO_INSTANT.format(record.getInstant()))
          .append(' ')
          .append(record.getLevel().getName())
          .append(" [")
          .append(record.getLoggerName() == null ? "" : record.getLoggerName())
          .append("] ")
          .append(formatMessage(record))
          .append(System.lineSeparator());

      if (record.getThrown() != null) {
        StringWriter writer = new StringWriter();
        record.getThrown().printStackTrace(new PrintWriter(writer));
        line.append(writer);
      }

      return line.toString();
    }
  }
}
```

`System.Logger.Level.DEBUG` maps to JUL `FINE`, which the default `INFO` root level filters out. Run with `-Dhandler.log.level=FINE` to see the scan timings and per-file decisions §11 describes.

- [ ] **Step 3: Write the failing CLI test**

Create `src/test/java/dev/theagencyhq/handler/tests/HandlerCLITest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.Handler;
import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.brief.Brief;
import dev.theagencyhq.handler.brief.FileBriefStore;
import dev.theagencyhq.handler.cli.HandlerCLI;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.config.HandlerPaths;
import dev.theagencyhq.handler.location.LocationScanner;

public class HandlerCLITest {
  private FakeAgency agency;
  private Path base;
  private ByteArrayOutputStream output;
  private FileBriefStore store;

  @Test
  public void helpAndVersionExitZero() {
    Assert.assertEquals(cli().run("help"), 0);
    Assert.assertEquals(cli().run("--version"), 0);
    Assert.assertTrue(output.toString().contains("handler"), "Output was: " + output);
  }

  @BeforeMethod
  public void setUp() throws IOException {
    base = Files.createDirectories(Path.of("build/test/cli-" + UUID.randomUUID()).toAbsolutePath());
    Files.createDirectories(base.resolve("locations"));
    store = new FileBriefStore(base.resolve("store"));
    output = new ByteArrayOutputStream();
    agency = new FakeAgency();
    agency.start();
  }

  @Test
  public void statusNamesEveryLocationAndItsStateAndWritesNothing() throws IOException {
    store.store(brief("42", 1));
    Path location = location("app", "42");
    Path orphan = location("orphan", "999");

    Assert.assertEquals(cli().run("status"), 0);

    String printed = output.toString();
    Assert.assertTrue(printed.contains(location.toString()), "Output was: " + printed);
    Assert.assertTrue(printed.contains("changed"), "Output was: " + printed);
    Assert.assertTrue(printed.contains(orphan.toString()), "Output was: " + printed);
    Assert.assertTrue(printed.contains("no brief"), "Output was: " + printed);

    // A pure read - status must not bootstrap a manifest
    Assert.assertFalse(Files.exists(location.resolve(".handler-manifest")));
    Assert.assertFalse(Files.exists(orphan.resolve(".handler-manifest")));
  }

  @Test
  public void statusNeverPrintsTheToken() throws IOException {
    store.store(brief("42", 1));
    location("app", "42");

    cli().run("status");

    Assert.assertFalse(output.toString().contains("super-secret"), "The token must never be printed");
    Assert.assertTrue(output.toString().contains("accessToken"), "Output was: " + output);
  }

  @Test
  public void syncExitsOneWhenALocationConflicts() throws IOException {
    agency.script(200, response("42", 1));
    Path location = location("app", "42");
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "unmanaged");

    Assert.assertEquals(cli().run("sync"), 1);
  }

  @Test
  public void syncExitsZeroAndForceAdoptsAConflict() throws IOException {
    agency.script(200, response("42", 1));
    agency.script(200, response("42", 1));
    Path location = location("app", "42");
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "unmanaged");

    Assert.assertEquals(cli().run("sync"), 1);
    Assert.assertEquals(cli().run("sync", "--force"), 0);
    Assert.assertEquals(Files.readString(location.resolve(".claude/a.md")), "alpha");
  }

  @AfterMethod
  public void tearDown() {
    agency.close();
  }

  @Test
  public void unknownSubcommandExitsOne() {
    Assert.assertEquals(cli().run("frobnicate"), 1);
  }

  private Brief brief(String organizationId, int version) {
    return Brief.fromJSON(briefJSON(organizationId, version).getBytes(StandardCharsets.UTF_8));
  }

  private String briefJSON(String organizationId, int version) {
    return """
        {"checksum":"opaque","organization":{"id":"%s","name":"Org"},"version":%d,"files":[\
        {"path":".claude/a.md","content":"alpha",\
        "checksum":"8ed3f6ad685b959ead7022518e1af76cd816f8e8ec7ccdda1ed4018e8f2223f8"}]}"""
        .formatted(organizationId, version);
  }

  private HandlerCLI cli() {
    HandlerConfig config = new HandlerConfig(base.resolve("locations").toString(), null, agency.url(), "super-secret",
                                            null, 3600, 3600);
    HandlerPaths paths = new HandlerPaths(base.resolve("handler.json"), base.resolve("store"),
                                          base.resolve("handler.log"));
    LocationScanner scanner = new LocationScanner(config);
    BriefPlanner planner = new BriefPlanner();
    LocationApplier applier = new LocationApplier();
    DistributeThread distributeThread = new DistributeThread(store, scanner, planner, applier);
    Handler handler = new Handler(config, new AgencyClient(config.theAgencyURL(), config::accessToken), store,
                                  distributeThread);

    return new HandlerCLI(paths, config, store, scanner, planner, applier, handler, new PrintStream(output, true));
  }

  private Path location(String relative, String organizationId) throws IOException {
    Path directory = Files.createDirectories(base.resolve("locations").resolve(relative));
    Files.writeString(directory.resolve("agent-location.json"),
                      "{\"version\":\"1.0.0\",\"organizationId\":\"" + organizationId + "\"}");
    return directory;
  }

  private String response(String organizationId, int version) {
    return "{\"organizationIds\":[\"" + organizationId + "\"],\"briefs\":[" + briefJSON(organizationId, version) + "]}";
  }
}
```

The checksum literal in `briefJSON` is the real SHA-256 of `alpha` (verified with `printf 'alpha' | shasum -a 256`), because `receiveAndDistribute` routes this Brief through `ReceiveThread`'s verification. If it ever fails, recompute it — never weaken the verification to make the test pass.

- [ ] **Step 4: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.HandlerCLITest`

Expected: FAIL at compilation — `package dev.theagencyhq.handler.cli does not exist`.

- [ ] **Step 5: Implement `HandlerCLI`**

Create `src/main/java/dev/theagencyhq/handler/cli/HandlerCLI.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;

// Disambiguates java.util.jar.Attributes from java.lang.classfile.Attributes, both pulled in by the module import
import java.util.jar.Attributes;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.Handler;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.apply.LocationPlan;
import dev.theagencyhq.handler.brief.BriefStore;
import dev.theagencyhq.handler.brief.StoredBrief;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.config.HandlerPaths;
import dev.theagencyhq.handler.location.Location;
import dev.theagencyhq.handler.location.LocationScanner;

/**
 * Argument dispatch and the three subcommands. {@code status} recomputes everything from disk rather than reading
 * persisted state, so there is nothing to go stale.
 *
 * @author Brian Pontarelli
 */
public class HandlerCLI {
  private final LocationApplier applier;
  private final HandlerConfig config;
  private final Handler handler;
  private final PrintStream out;
  private final HandlerPaths paths;
  private final BriefPlanner planner;
  private final LocationScanner scanner;
  private final BriefStore store;

  public HandlerCLI(HandlerPaths paths, HandlerConfig config, BriefStore store, LocationScanner scanner,
                    BriefPlanner planner, LocationApplier applier, Handler handler, PrintStream out) {
    this.paths = paths;
    this.config = config;
    this.store = store;
    this.scanner = scanner;
    this.planner = planner;
    this.applier = applier;
    this.handler = handler;
    this.out = out;
  }

  /**
   * Reads the version the build stamped into the jar's manifest. {@code Package.getImplementationVersion()} does not
   * work here — the JDK does not carry manifest attributes onto packages defined in a named module — so the manifest
   * is read straight out of this module.
   *
   * @return The jar's {@code Implementation-Version}, or {@code "dev"} when running from exploded classes, where
   *     there is no manifest to read.
   */
  private static String version() {
    try (InputStream is = HandlerCLI.class.getModule().getResourceAsStream("META-INF/MANIFEST.MF")) {
      if (is == null) {
        return "dev";
      }

      String version = new Manifest(is).getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
      return version == null ? "dev" : version;
    } catch (IOException e) {
      return "dev";
    }
  }

  public int run(String... args) {
    String command = args.length == 0 ? "daemon" : args[0];
    return switch (command) {
      case "daemon" -> {
        handler.daemon();
        yield 0;
      }
      case "sync" -> sync(Arrays.asList(args).contains("--force"));
      case "status" -> status();
      case "help", "--help", "-h" -> {
        usage();
        yield 0;
      }
      case "--version", "version" -> {
        out.println("handler " + version());
        yield 0;
      }
      default -> {
        out.println("Unknown command [" + command + "]");
        usage();
        yield 1;
      }
    };
  }

  private LocationPlan planFor(Location location) {
    Optional<StoredBrief> stored = store.latest(location.organizationId());
    if (stored.isEmpty() || store.revoked(location.organizationId())) {
      return LocationPlan.EMPTY;
    }

    try {
      return planner.plan(stored.get(), location);
    } catch (BriefPlanner.InvalidPlanException e) {
      return null;
    }
  }

  private int status() {
    out.println("configFile   " + paths.configFile());
    out.println("storeRoot    " + paths.storeRoot());
    out.println("logFile      " + paths.logFile());
    out.println("theAgencyURL " + config.theAgencyURL());
    out.println("accessToken  " + (config.accessToken().isEmpty() ? "absent" : "present"));
    out.println();

    out.println("Organizations");
    Set<String> organizationIds = store.organizationIds();
    if (organizationIds.isEmpty()) {
      out.println("  (none)");
    } else {
      for (String organizationId : organizationIds) {
        String version = store.latest(organizationId)
                              .map(stored -> Integer.toString(stored.version()))
                              .orElse("none");
        out.println("  " + organizationId + "  version=" + version
            + (store.revoked(organizationId) ? "  revoked" : ""));
      }
    }

    out.println();
    out.println("Locations");
    List<Location> locations = scanner.scan();
    if (locations.isEmpty()) {
      out.println("  (none)");
      return 0;
    }

    for (Location location : locations) {
      out.println("  " + location.root() + "  " + describe(location));
    }

    return 0;
  }

  private String describe(Location location) {
    if (store.latest(location.organizationId()).isEmpty() && !store.revoked(location.organizationId())) {
      return "no brief";
    }

    LocationPlan plan = planFor(location);
    if (plan == null) {
      return "invalid brief";
    }

    return switch (applier.inspect(location, plan)) {
      case CHANGED -> "changed";
      case CONFLICT -> "conflict";
      case UNCHANGED -> "unchanged";
      case UNREADABLE -> "unreadable";
    };
  }

  private int sync(boolean force) {
    DistributeThread.Summary summary = handler.receiveAndDistribute(force);
    return summary.clean() ? 0 : 1;
  }

  private void usage() {
    out.println("""
        Usage: handler [command]
        
          daemon             Run the receive and distribute loops in the foreground (default)
          sync [--force]     Run one receive pass then one distribute pass, then exit
          status             Print resolved paths, stored Organizations, and every Location's state
          help               Print this message
          --version          Print the version
        """);
  }
}
```

- [ ] **Step 6: Implement `Main`**

Create `src/main/java/dev/theagencyhq/handler/Main.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler;

import module java.base;

import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.agency.ConfigTokenSupplier;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.brief.BriefStore;
import dev.theagencyhq.handler.brief.FileBriefStore;
import dev.theagencyhq.handler.cli.HandlerCLI;
import dev.theagencyhq.handler.config.ConfigLoader;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.config.HandlerPaths;
import dev.theagencyhq.handler.location.LocationScanner;
import dev.theagencyhq.handler.log.Logging;

/**
 * The entry point. The only place that resolves paths from the environment and wires the object graph.
 *
 * @author Brian Pontarelli
 */
public final class Main {
  private Main() {
  }

  public static void main(String[] args) {
    HandlerPaths paths = HandlerPaths.fromEnvironment();
    Logging.configure(paths);

    HandlerConfig config;
    try {
      config = new ConfigLoader(paths, System::getenv).load();
    } catch (ConfigLoader.MalformedConfigException e) {
      // Guessing at intent here would silently sync from the wrong Agency
      System.err.println(e.getMessage());
      System.exit(2);
      return;
    }

    BriefStore store = new FileBriefStore(paths.storeRoot());
    LocationScanner scanner = new LocationScanner(config);
    BriefPlanner planner = new BriefPlanner();
    LocationApplier applier = new LocationApplier();
    DistributeThread distributeThread = new DistributeThread(config, store, scanner, planner, applier);
    AgencyClient agency = new AgencyClient(config.theAgencyURL(), new ConfigTokenSupplier(config));
    Handler handler = new Handler(config, agency, store, distributeThread);
    Runtime.getRuntime().addShutdownHook(new Thread(handler::shutdown, "handler-shutdown"));

    HandlerCLI cli = new HandlerCLI(paths, config, store, scanner, planner, applier, handler, System.out);
    System.exit(cli.run(args));
  }
}
```

- [ ] **Step 7: Export the new packages and run the tests**

`src/main/java/module-info.java` in final form:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
module dev.theagencyhq.handler {
  requires java.logging;
  requires java.net.http;
  requires static org.lattejava.json;

  exports dev.theagencyhq.handler;
  exports dev.theagencyhq.handler.agency;
  exports dev.theagencyhq.handler.apply;
  exports dev.theagencyhq.handler.brief;
  exports dev.theagencyhq.handler.cli;
  exports dev.theagencyhq.handler.config;
  exports dev.theagencyhq.handler.location;
  exports dev.theagencyhq.handler.log;
}
```

Run: `latte test --test=dev.theagencyhq.handler.tests.HandlerCLITest`
Then: `latte test`

Expected: PASS across every test class.

- [ ] **Step 8: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: add logging configuration, the CLI, and the entry point"
```

---

### Task 15: End-to-end integration tests

The last task. Everything here is a full `receive → store → distribute → apply` cycle against the fake Agency, covering the §13 integration list that the per-component tests do not already reach.

**Files:**
- Test: `src/test/java/dev/theagencyhq/handler/tests/IntegrationTest.java`

**Interfaces:**
- Consumes: everything. Adds nothing to production code.

- [ ] **Step 1: Write the integration tests**

Create `src/test/java/dev/theagencyhq/handler/tests/IntegrationTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.Handler;
import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.apply.Manifest;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.location.LocationScanner;

import static org.testng.Assert.*;

public class IntegrationTest extends BaseTest {
  private FakeAgency agency;

  @Test
  public void agencyUnreachableStillDistributesFromTheStore() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    agency.close();     // every request now fails

    handler().receiveAndDistribute(false);

    assertEquals(Files.readString(location.resolve(".claude/a.md")), "alpha");
  }

  @Test
  public void crashMidApplyConvergesOnTheNextCycle() throws IOException {
    agency.script(200, response("42", 1, file(".claude/rules/a.md", "alpha")));
    Path location = location("app", "42");

    // A manifest listing entries whose files were never written - exactly what a kill between the flushed append and
    // the file write leaves behind
    Files.writeString(location.resolve(Manifest.FILENAME), "0.1.0\n.claude/\n.claude/rules/\n.claude/rules/a.md\n");

    DistributeThread.Summary summary = handler().receiveAndDistribute(false);

    assertTrue(summary.clean(), "Recovery must not report a conflict or a failure: " + summary);
    assertEquals(Files.readString(location.resolve(".claude/rules/a.md")), "alpha");
  }

  @Test
  public void emptyBriefTearsDownButLeavesTheManifestExcluded() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    agency.script(200, response("42", 2));
    Path location = location("app", "42");

    handler().receiveAndDistribute(false);
    assertTrue(Files.exists(location.resolve(".claude/a.md")));

    handler().receiveAndDistribute(false);

    assertFalse(Files.exists(location.resolve(".claude")), "The teardown removes everything it created");
    assertFalse(Files.exists(location.resolve(".gitignore")), "The Handler never writes the team's .gitignore");
    assertTrue(Files.readAllLines(location.resolve(".git/info/exclude")).contains(Manifest.FILENAME),
               "The manifest survives a teardown, so its exclude line must too");
  }

  @Test
  public void gitExcludeCarriesEveryManagedFileAndIsCleanedUp() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    agency.script(200, response("42", 2, file(".claude/b.md", "bravo")));

    Path location = location("app", "42");

    // First response from the agency (version 1)
    handler().receiveAndDistribute(false);
    assertEquals(Files.readAllLines(location.resolve(".git/info/exclude")),
                 List.of(Manifest.FILENAME, ".claude/a.md", Manifest.STAGING_DIRECTORY + "/"));

    // Second response from the agency (version 2)
    handler().receiveAndDistribute(false);
    assertEquals(Files.readAllLines(location.resolve(".git/info/exclude")),
                 List.of(Manifest.FILENAME, Manifest.STAGING_DIRECTORY + "/", ".claude/b.md"),
                 "The dropped file's line must be removed and the new one added");
  }

  @Test
  public void notModifiedStillPopulatesANewlyCreatedLocation() throws IOException {
    store.store(brief("42", 7, file(".claude/a.md", "alpha")));
    agency.script(304, "");

    Path location = location("brand-new", "42");
    FileTime storeTime = Files.getLastModifiedTime(storeRoot().resolve("42/7/brief.json"));

    handler().receiveAndDistribute(false);

    assertEquals(Files.readString(location.resolve(".claude/a.md")), "alpha");
    assertEquals(Files.getLastModifiedTime(storeRoot().resolve("42/7/brief.json")), storeTime, "A 304 performs no store writes");
  }

  @Test
  public void revocationTearsDownEveryLocationThenPurgesTheStore() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    agency.script(200, "{\"organizationIds\":[],\"briefs\":[]}");

    Path one = location("one", "42");
    Path two = location("two", "42");

    handler().receiveAndDistribute(false);
    assertTrue(Files.exists(one.resolve(".claude/a.md")));
    assertTrue(Files.exists(two.resolve(".claude/a.md")));

    handler().receiveAndDistribute(false);

    assertFalse(Files.exists(one.resolve(".claude")));
    assertFalse(Files.exists(two.resolve(".claude")));
    assertFalse(Files.exists(storeRoot().resolve("42")), "The store entry is purged after a clean teardown");
  }

  @BeforeMethod
  public void setUp() {
    agency = new FakeAgency();
    agency.start();
  }

  @AfterMethod
  public void tearDown() {
    agency.close();
  }

  @Test
  public void unchangedInputTouchesNothingAcrossTwoCycles() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    agency.script(304, "");

    Path location = location("app", "42");

    handler().receiveAndDistribute(false);

    Path applied = location.resolve(".claude/a.md");
    Path manifest = location.resolve(Manifest.FILENAME);
    FileTime fileTime = Files.getLastModifiedTime(applied);
    FileTime manifestTime = Files.getLastModifiedTime(manifest);

    handler().receiveAndDistribute(false);

    assertEquals(Files.getLastModifiedTime(applied), fileTime);
    assertEquals(Files.getLastModifiedTime(manifest), manifestTime);
  }

  @Test
  public void versionBumpReplacesFilesAndRemovesDroppedOnes() throws IOException {
    agency.script(200, response("42", 1, file(".claude/old.md", "old"), file(".claude/both.md", "first")));
    agency.script(200, response("42", 2, file(".claude/both.md", "second"), file(".claude/new.md", "new")));

    Path location = location("app", "42");

    handler().receiveAndDistribute(false);
    handler().receiveAndDistribute(false);

    assertEquals(Files.readString(location.resolve(".claude/both.md")), "second");
    assertEquals(Files.readString(location.resolve(".claude/new.md")), "new");
    assertFalse(Files.exists(location.resolve(".claude/old.md")));
    assertEquals(store.latest("42").orElseThrow().version(), 2);
  }

  private Handler handler() throws IOException {
    HandlerConfig config = new HandlerConfig(locations().toString(), null, agency.url(), "token", null, 3600, 3600);
    LocationScanner scanner = new LocationScanner(config);
    DistributeThread distributeThread = new DistributeThread(config, store, scanner, new BriefPlanner(),
                                                             new LocationApplier());
    return new Handler(config, new AgencyClient(config.theAgencyURL(), config::accessToken), store, distributeThread);
  }
}
```

Each test builds a fresh `Handler` per `receiveAndDistribute` call, which is deliberate: it proves the cycle carries no in-memory state between runs and that the store on disk is genuinely the only handoff.

- [ ] **Step 2: Run the integration tests**

Run: `latte test --test=dev.theagencyhq.handler.tests.IntegrationTest`

Expected: PASS, 8 tests.

`agencyUnreachableStillDistributesFromTheStore` closes the fake Agency mid-test, so the client must wait out a connection refusal — that is fast on localhost, but if the test is slow, confirm `AgencyClient` is not retrying internally. It should not be; retry is the interval loop's job.

- [ ] **Step 3: Run the entire suite and confirm the build is clean**

Run: `latte clean && latte test`

Expected: every test class passes from a cold build. Record the actual counts in the commit message rather than a claim of success.

- [ ] **Step 4: Commit**

```bash
git add src/test/java
git commit -m "test: add end-to-end receive, store, distribute, and apply integration tests"
```

---

## Self-Review Notes

Run through these before declaring the plan done. They are the gaps found while writing it, recorded so the implementer does not rediscover them.

**Spec coverage.** Every numbered spec section maps to a task:

| Spec | Task |
|------|------|
| §6 config, XDG paths, exclusion matching | 1, 2 |
| §7.1–7.2 token supplier, client, sealed result | 5 |
| §7.3, §14 `@JSONRaw` capture | 3 |
| §7.4 store layout, atomic write, `latest` rules, revoke, purge | 4 |
| §7.5 receive cycle, checksum verification, nudge | 11 |
| §8.1 scanner | 6 |
| §8.2 mission types | 6 |
| §8.3 planner, path validation | 9 |
| §8.4 manifest | 7 |
| §8.5 git exclude | 8 |
| §8.6 applier | 10 |
| §8.7–8.8 distribute, revocation completion | 12 |
| §9 interval loop, nudge, lock discipline | 11 |
| §9 startup pass, thread start order, shutdown | 13 |
| §10 CLI | 14 |
| §11 logging | 14 |
| §13 tests | every task, plus 15 |

**Deliberate deviations from the spec, each flagged in its task for rejection:**

1. `BriefFile.decoded()` / `posixMode()` live on the model rather than in `BriefPlanner` (§8.3), because `ReceiveThread` (§7.5) needs the same decode.
2. `BriefingResult.Failed` gains `boolean authenticationFailure` so §4's "401 logs at ERROR, 5xx logs at WARNING" is implementable without sniffing a reason string.
3. The change check compares bytes with `Arrays.equals` against `readAllBytes` rather than `Files.mismatch`, because the planned content is in memory and §8.6 forbids writes on the unchanged path.
4. `LocationApplier.inspect` and `FileManifest.peek` are additions, not in the spec — required because §10 says `handler status` "writes nothing" while §8.6's step 0 bootstraps a manifest.
5. `Handler.receiveOnce()` is public so the nudge is testable without waiting out the 10-second interval floor.
6. An invalid plan counts as `failed` in the distribute summary (§8.3 says only "skipped with an ERROR").

**Open items that are not this plan's to resolve:**

- **No collection entry point exists on generated `@JSON` types** — verified against the processor's codegen tests, only `fromJSON(String)` and `fromJSON(byte[])` are generated. This is why `BriefingResponse` is created in Task 3 rather than Task 5: a bare array of Briefs cannot be deserialized on its own. Nothing further is needed here, but expect the same constraint for any future list-shaped payload.
- **`idea.md` lives at `./idea.md`** (repository root, not under `docs/`). §8.2's truth table is transcribed from it verbatim in Task 6. Worth knowing while implementing: the design doc deliberately departs from `idea.md` in one place that is easy to trip over, beyond the five already listed in spec §3.1. `idea.md`'s "## API" section says the Brief checksum is "based on the full JSON response" and that "the Handler will SHA256 checksum the JSON file in the store and send the checksum"; design §4 instead makes the Brief checksum **opaque** — stored, echoed back verbatim, never computed. This plan follows the design, and `CurrentVersion.checksum` is therefore always `storedBrief.brief().checksum()` and never a computed digest. Per-*file* checksums are still verified locally (§7.3, Task 11).
- **Checksums in fixtures are real values, computed 2026-07-29.** `briefing-updated.json` carries the true SHA-256 of `For Claude` and `HandlerCLITest.briefJSON` the true SHA-256 of `alpha`. The one deliberate exception is `briefing-tricky.json`, whose `checksum` reads `ignored-by-this-test` — `BriefTest` never verifies checksums and that fixture exists only to stress the raw capture. If that fixture is ever routed through `ReceiveThread`, it needs a real value first.
- **The version comes from the jar manifest, not a constant.** `latte` does *not* stamp a module version into the jar — `--describe-module` reports the module with no version and `ModuleDescriptor.rawVersion()` is empty — but it does write `Implementation-Version` into `META-INF/MANIFEST.MF`. `Package.getImplementationVersion()` returns null here, because the JDK only carries manifest attributes onto packages it defines for the class path, not for a named module; `HandlerCLI.version()` therefore reads the manifest out of its own module and falls back to `"dev"` for exploded-classes runs.

**Type consistency check performed:** `HandlerPaths(configFile, storeRoot, logFile)` component order is identical in Tasks 1, 2, and 14. `ApplyResult` is used for `apply` and `LocationState` for `inspect` — deliberately different types, never interchanged. `DistributeThread.Summary(applied, unchanged, conflict, failed)` order is identical in Tasks 12, 14, and 15. `DistributeThread(config, store, scanner, planner, applier)` and `ReceiveThread(config, agency, store, distributeThread)` both take `HandlerConfig` first, because `IntervalThread.intervalSeconds()` is read fresh before every wait. `Manifest.Entry(path, directory)` order is identical in Tasks 7, 10, and 14.

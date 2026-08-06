# Handler Login with FusionAuth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `handler login`, `handler logout`, and automatic refresh-grant token renewal to the Handler, authenticating against FusionAuth with the OAuth 2.0 Authorization Code flow and PKCE.

**Architecture:** A new `dev.theagencyhq.handler.auth` package holds the flow. `handler login` starts a single-use HTTP server on an ephemeral loopback port, opens the browser to FusionAuth, captures the authorization code on the redirect, and exchanges it for tokens that land in a machine-managed `tokens.json`. `TokenSupplier` grows a `refresh()` method that `AgencyClient` calls on a `401` before retrying the request once.

**Tech Stack:** Java 25, JPMS modules, Latte build tool (`latte build`, `latte test`), TestNG, `org.lattejava:json` compile-time annotation processor, `com.sun.net.httpserver`, FusionAuth in Docker.

**Source of truth:** `docs/design/2026-08-04-handler-login-design.md`. Read it before starting.

## Global Constraints

- **Java 25.** Both `src/main` and `src/test` are JPMS modules. New external dependencies require updating `project.latte` **and** the right `module-info.java`. This plan adds no external dependencies.
- **Build with Latte, never Maven or Gradle.** `latte build` compiles and jars; `latte test` depends on `build`. Single test: `latte test --test=dev.theagencyhq.handler.tests.TokenStoreTest`.
- **Copyright header on every new Java file**, exactly:
  ```java
  /*
   * Copyright (c) 2026 The Agency HQ
   * SPDX-License-Identifier: MIT
   */
  ```
- **2-space indent, 4-space continuation indent. 120-character target line length.** Do not wrap before 120.
- **Acronyms are fully uppercase in identifiers:** `JWTs`, `PKCE`, `OAuthClient`, `redirectURI`, `authorizeURL`, `toJSON`. Never `Jwt`, `Uri`, `Url`, `Json`.
- **Alphabetize** fields (grouped by visibility), methods (within visibility/kind group), imports, and `requires`/`exports` clauses in `module-info.java`.
- **Class member order:** static fields, instance fields, constructors (by parameter count), static methods, instance methods, inner classes. No blank lines between fields.
- **Prefer module imports** (`import module java.base;`) over class imports, matching every existing file.
- **Runtime values in all error and log messages go in square brackets:** `"Invalid issuer URL [" + issuer + "]"`. Never quotes.
- **Tokens are never logged at any level.**
- **Javadoc uses American English sentence structure, punctuation, and capitalization** — on the class and on any method whose contract is not obvious. Every existing class in this project has a class-level Javadoc ending with `@author Brian Pontarelli`. Match that.
- **Git:** never commit to `main`. All work lands on the current branch, `feat/fusionauth-login`. Commit messages follow Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`).
- **Tests extend `BaseTest`** (`src/test/java/dev/theagencyhq/handler/tests/BaseTest.java`), which provides a scratch `base` directory per method. Tests live in `dev.theagencyhq.handler.tests`.
- **FusionAuth is already running** on `http://localhost:9015` for this implementation session. Task 8 provisions the files that start it.

## File Structure

**Created — main:**

| File | Responsibility |
|---|---|
| `src/main/java/dev/theagencyhq/handler/auth/AccessTokenClaims.java` | The claims the Handler reads off an access token |
| `src/main/java/dev/theagencyhq/handler/auth/AuthConfiguration.java` | Issuer resolution and OAuth URL construction |
| `src/main/java/dev/theagencyhq/handler/auth/Browser.java` | Functional interface for opening a URL |
| `src/main/java/dev/theagencyhq/handler/auth/Browsers.java` | Production browser launcher |
| `src/main/java/dev/theagencyhq/handler/auth/JWTs.java` | JWT payload claim reader |
| `src/main/java/dev/theagencyhq/handler/auth/Login.java` | Orchestrates the whole login flow |
| `src/main/java/dev/theagencyhq/handler/auth/LoopbackServer.java` | Single-use loopback callback server |
| `src/main/java/dev/theagencyhq/handler/auth/OAuthClient.java` | Token endpoint calls |
| `src/main/java/dev/theagencyhq/handler/auth/OAuthTokenSupplier.java` | `TokenSupplier` with refresh |
| `src/main/java/dev/theagencyhq/handler/auth/TokenResponse.java` | Token endpoint JSON body |
| `src/main/java/dev/theagencyhq/handler/auth/Tokens.java` | The token pair and the `tokens.json` shape |
| `src/main/java/dev/theagencyhq/handler/auth/TokenStore.java` | Reads and writes `tokens.json` |
| `src/main/java/dev/theagencyhq/handler/auth/AuthenticationException.java` | The auth package's failure type |
| `src/main/resources/auth/success.html`, `error.html` | Browser result pages |

**Modified — main:** `module-info.java`, `Main.java`, `cli/HandlerCLI.java`, `config/HandlerConfig.java`, `config/HandlerPaths.java`, `config/ConfigLoader.java`, `agency/TokenSupplier.java`, `agency/AgencyClient.java`. **Deleted:** `agency/ConfigTokenSupplier.java`.

**Created — test:** `AuthConfigurationTest`, `FakeIdP`, `FusionAuthBrowser`, `JWTsTest`, `LoginTest`, `LoopbackServerTest`, `OAuthClientTest`, `OAuthTokenSupplierTest`, `PKCETest`, `StubTokenSupplier`, `TokenStoreTest`, plus `src/test/fusionauth/`.

---

### Task 1: Token storage and the config file

Moves the tokens out of `handler.json` into a machine-managed `tokens.json`, and adds `authURL` to the config.

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/auth/Tokens.java`
- Create: `src/main/java/dev/theagencyhq/handler/auth/TokenStore.java`
- Create: `src/main/java/dev/theagencyhq/handler/auth/AuthenticationException.java`
- Modify: `src/main/java/dev/theagencyhq/handler/config/HandlerPaths.java`
- Modify: `src/main/java/dev/theagencyhq/handler/config/HandlerConfig.java`
- Modify: `src/main/java/dev/theagencyhq/handler/config/ConfigLoader.java:40` and `:60`
- Modify: `src/main/java/dev/theagencyhq/handler/module-info.java` — add `exports dev.theagencyhq.handler.auth;`
- Test: `src/test/java/dev/theagencyhq/handler/tests/TokenStoreTest.java` (create)
- Test: `src/test/java/dev/theagencyhq/handler/tests/HandlerPathsTest.java` (modify)
- Test: `src/test/java/dev/theagencyhq/handler/tests/HandlerConfigTest.java` (modify)

**Interfaces:**
- Produces:
  - `record Tokens(String accessToken, String refreshToken)` with `Tokens.EMPTY`, `Tokens.fromJSON(byte[])`, `toJSON()`, `toPrettyString()`, and `boolean present()` (true when `accessToken` is non-empty).
  - `class TokenStore` with `TokenStore(Path tokensFile)`, `Tokens load()`, `void store(Tokens)`, `boolean clear()`.
  - `class AuthenticationException extends RuntimeException` with `(String)` and `(String, Throwable)` constructors.
  - `HandlerPaths` becomes `record HandlerPaths(Path configFile, Path tokensFile, Path storeRoot, Path logFile)`.
  - `HandlerConfig` becomes `record HandlerConfig(String startDirectory, List<String> excludeDirectories, String theAgencyURL, String authURL, int receiveIntervalSeconds, int distributeIntervalSeconds)` with `DEFAULT_AUTH_URL = "https://auth.theagencyhq.dev"`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/TokenStoreTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.auth.TokenStore;
import dev.theagencyhq.handler.auth.Tokens;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TokenStoreTest extends BaseTest {
  @Test
  public void absentFileLoadsEmptyRatherThanFailing() {
    // Not logged in is a normal state, not an error
    Tokens tokens = new TokenStore(base.resolve("missing/tokens.json")).load();

    assertEquals(tokens, Tokens.EMPTY);
    assertFalse(tokens.present());
  }

  @Test
  public void clearRemovesTheFileAndReportsWhetherAnythingWasThere() {
    TokenStore store = new TokenStore(tokensFile());

    assertFalse(store.clear(), "Nothing was stored, so clear should report false");

    store.store(new Tokens("access", "refresh"));
    assertTrue(store.clear(), "A stored token should make clear report true");
    assertFalse(Files.exists(tokensFile()));
  }

  @Test
  public void storeCreatesParentDirectoriesAndRoundTrips() {
    TokenStore store = new TokenStore(tokensFile());

    store.store(new Tokens("access-1", "refresh-1"));

    Tokens loaded = store.load();
    assertEquals(loaded.accessToken(), "access-1");
    assertEquals(loaded.refreshToken(), "refresh-1");
    assertTrue(loaded.present());
  }

  @Test
  public void storeRestrictsPermissionsToTheOwner() throws IOException {
    new TokenStore(tokensFile()).store(new Tokens("access", "refresh"));

    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tokensFile());
    assertEquals(permissions, PosixFilePermissions.fromString("rw-------"));
  }

  @Test
  public void storeReplacesRatherThanAppends() {
    TokenStore store = new TokenStore(tokensFile());

    store.store(new Tokens("first", "first-refresh"));
    store.store(new Tokens("second", "second-refresh"));

    assertEquals(store.load().accessToken(), "second");
    assertEquals(store.load().refreshToken(), "second-refresh");
  }

  @Test
  public void aTokenPairWithNoRefreshTokenRoundTripsAsEmpty() {
    TokenStore store = new TokenStore(tokensFile());

    store.store(new Tokens("access-only", null));

    assertEquals(store.load().accessToken(), "access-only");
    assertEquals(store.load().refreshToken(), "");
  }

  private Path tokensFile() {
    return base.resolve("config/tokens.json");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.TokenStoreTest`
Expected: compilation failure — `package dev.theagencyhq.handler.auth does not exist`.

- [ ] **Step 3: Create `AuthenticationException`**

Create `src/main/java/dev/theagencyhq/handler/auth/AuthenticationException.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

/**
 * Thrown when any step of the login flow fails. The message is written for a developer reading a terminal, because
 * that is the only place it is ever shown.
 *
 * @author Brian Pontarelli
 */
public class AuthenticationException extends RuntimeException {
  public AuthenticationException(String message) {
    super(message);
  }

  public AuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

- [ ] **Step 4: Create `Tokens`**

Create `src/main/java/dev/theagencyhq/handler/auth/Tokens.java`. Model it on `HandlerConfig` — same `@JSON` annotation, same generated-companion delegation, same compact-constructor normalization:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.auth.internal.TokensJSON;

/**
 * The OAuth tokens from a successful grant, and the on-disk shape of {@code tokens.json}. Both fields are normalized
 * to the empty string rather than null, so no caller has to defend against a raw form.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Tokens(String accessToken, String refreshToken) {
  public static final Tokens EMPTY = new Tokens("", "");

  public Tokens {
    accessToken = accessToken == null ? "" : accessToken.trim();
    refreshToken = refreshToken == null ? "" : refreshToken.trim();
  }

  public static Tokens fromJSON(byte[] json) {
    return TokensJSON.fromJSON(json);
  }

  /**
   * @return Whether an access token is present. An absent {@code tokens.json} and a cleared one both yield false.
   */
  public boolean present() {
    return !accessToken.isEmpty();
  }

  public String toJSON() {
    return TokensJSON.toJSON(this);
  }

  public String toPrettyString() {
    return TokensJSON.toPrettyString(this);
  }
}
```

- [ ] **Step 5: Create `TokenStore`**

Create `src/main/java/dev/theagencyhq/handler/auth/TokenStore.java`. The write must go through a sibling temp file and an atomic move, so an interrupted write never truncates a working token file:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Reads and writes {@code tokens.json}. This file is machine-managed state, not configuration — it is rewritten
 * whenever a login or a refresh produces new tokens, so it deliberately does not share a file with the hand-edited
 * {@code handler.json}.
 *
 * <p>Writes go through a sibling temp file that is then moved into place atomically, so a crash mid-write leaves the
 * previous tokens intact rather than a truncated file that would force a re-login.
 *
 * @author Brian Pontarelli
 */
public class TokenStore {
  private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");

  private final Path tokensFile;

  public TokenStore(Path tokensFile) {
    this.tokensFile = tokensFile;
  }

  /**
   * Deletes the token file.
   *
   * @return Whether a token file was present and removed.
   */
  public boolean clear() {
    try {
      return Files.deleteIfExists(tokensFile);
    } catch (IOException e) {
      throw new AuthenticationException("Unable to remove the token file [" + tokensFile + "]. Message was ["
          + e.getMessage() + "]", e);
    }
  }

  /**
   * @return The stored tokens, or {@link Tokens#EMPTY} when the file is absent. Not being logged in is a normal state.
   */
  public Tokens load() {
    if (!Files.isRegularFile(tokensFile)) {
      return Tokens.EMPTY;
    }

    try {
      return Tokens.fromJSON(Files.readAllBytes(tokensFile));
    } catch (IOException e) {
      throw new AuthenticationException("Unable to read the token file [" + tokensFile + "]. Message was ["
          + e.getMessage() + "]", e);
    } catch (RuntimeException e) {
      throw new AuthenticationException("The token file [" + tokensFile + "] is malformed. Run [handler login] to"
          + " replace it. Message was [" + e.getMessage() + "]", e);
    }
  }

  public void store(Tokens tokens) {
    Path directory = tokensFile.toAbsolutePath().getParent();
    Path temp = null;
    try {
      Files.createDirectories(directory);
      temp = Files.createTempFile(directory, "tokens", ".json");
      Files.writeString(temp, tokens.toPrettyString() + "\n");
      Files.setPosixFilePermissions(temp, OWNER_READ_WRITE);

      try {
        Files.move(temp, tokensFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, tokensFile, StandardCopyOption.REPLACE_EXISTING);
      }
      temp = null;
    } catch (IOException e) {
      throw new AuthenticationException("Unable to write the token file [" + tokensFile + "]. Message was ["
          + e.getMessage() + "]", e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // Best-effort cleanup of the temp file; there is nothing actionable if it cannot be removed
        }
      }
    }
  }
}
```

- [ ] **Step 6: Export the new package**

In `src/main/java/module-info.java`, add `exports dev.theagencyhq.handler.auth;` to the `exports` list, keeping it alphabetized (it goes after `dev.theagencyhq.handler.apply;` and before `dev.theagencyhq.handler.brief;`).

- [ ] **Step 7: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.TokenStoreTest`
Expected: PASS, 6 tests.

- [ ] **Step 8: Add `tokensFile` to `HandlerPaths`**

`HandlerPaths` becomes a four-component record. Change the record header to:

```java
public record HandlerPaths(Path configFile, Path tokensFile, Path storeRoot, Path logFile) {
```

and in `resolve`, return:

```java
    return new HandlerPaths(config.resolve(VENDOR_DIRECTORY).resolve("handler.json"),
                            config.resolve(VENDOR_DIRECTORY).resolve("tokens.json"),
                            data.resolve(VENDOR_DIRECTORY).resolve("briefs"),
                            state.resolve(VENDOR_DIRECTORY).resolve("handler.log"));
```

Update the class Javadoc: it says "The three filesystem locations the Handler owns" — make it "The four filesystem locations the Handler owns".

- [ ] **Step 9: Add a `HandlerPathsTest` assertion**

Read `src/test/java/dev/theagencyhq/handler/tests/HandlerPathsTest.java` first and follow its existing style. Add:

```java
  @Test
  public void tokensFileSitsBesideTheConfigFile() {
    HandlerPaths paths = HandlerPaths.resolve(name -> null, Path.of("/home/dev"));

    assertEquals(paths.tokensFile(), Path.of("/home/dev/.config/the-agency-hq/tokens.json"));
    assertEquals(paths.tokensFile().getParent(), paths.configFile().getParent());
  }
```

Fix any existing test in that file that constructs `HandlerPaths` positionally.

- [ ] **Step 10: Change `HandlerConfig`**

Replace `accessToken` and `refreshToken` with `authURL`. The new record header and the relevant compact-constructor lines:

```java
@JSON
public record HandlerConfig(String startDirectory, List<String> excludeDirectories, String theAgencyURL,
                            String authURL, int receiveIntervalSeconds, int distributeIntervalSeconds) {
  public static final String DEFAULT_AUTH_URL = "https://auth.theagencyhq.dev";
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
    authURL = authURL == null || authURL.isBlank() ? DEFAULT_AUTH_URL : stripTrailingSlash(authURL.trim());
    receiveIntervalSeconds = interval(receiveIntervalSeconds, DEFAULT_RECEIVE_INTERVAL_SECONDS);
    distributeIntervalSeconds = interval(distributeIntervalSeconds, DEFAULT_DISTRIBUTE_INTERVAL_SECONDS);
  }
```

Leave everything else in the class alone. Keep the constants alphabetized as shown.

- [ ] **Step 11: Update `ConfigLoader`**

At `ConfigLoader.java:40`, the environment-override branch reconstructs the config. Replace it with:

```java
      config = new HandlerConfig(override, config.excludeDirectories(), config.theAgencyURL(), config.authURL(),
                                 config.receiveIntervalSeconds(), config.distributeIntervalSeconds());
```

At `ConfigLoader.java:60`, the default-file branch has one fewer argument:

```java
    HandlerConfig config = new HandlerConfig(null, null, null, null, 0, 0);
```

- [ ] **Step 12: Fix every other `HandlerConfig` construction**

These call sites pass the old seven arguments and will not compile. In each, delete the `accessToken` and `refreshToken` arguments and add `null` for `authURL` in their place (the third-from-last position becomes `authURL`, immediately after `theAgencyURL`):

- `src/test/java/dev/theagencyhq/handler/tests/ReceiveThreadTest.java:128`
- `src/test/java/dev/theagencyhq/handler/tests/HandlerCLITest.java:127`
- `src/test/java/dev/theagencyhq/handler/tests/DistributeThreadTest.java:273`
- `src/test/java/dev/theagencyhq/handler/tests/HandlerTest.java:146`
- `src/test/java/dev/theagencyhq/handler/tests/LocationScannerTest.java:146`
- `src/test/java/dev/theagencyhq/handler/tests/IntegrationTest.java:169`
- `src/test/java/dev/theagencyhq/handler/tests/HandlerConfigTest.java:50` and `:66`

For example, `ReceiveThreadTest.java:128` becomes:

```java
    HandlerConfig config = new HandlerConfig(base.toString(), null, agency.url(), null, 3600, 3600);
```

`Main.java` constructs `AgencyClient` with `new ConfigTokenSupplier(config)`; leave that alone for now — Task 6 replaces it. If `ConfigTokenSupplier` fails to compile because it calls `config.accessToken()`, make it return `""` temporarily with a `// Replaced in Task 6` comment.

- [ ] **Step 13: Add a `HandlerConfigTest` case for `authURL`**

```java
  @Test
  public void authURLDefaultsAndStripsATrailingSlash() {
    assertEquals(new HandlerConfig(null, null, null, null, 0, 0).authURL(), "https://auth.theagencyhq.dev");
    assertEquals(new HandlerConfig(null, null, null, "  ", 0, 0).authURL(), "https://auth.theagencyhq.dev");
    assertEquals(new HandlerConfig(null, null, null, "http://localhost:9015/", 0, 0).authURL(),
                 "http://localhost:9015");
  }
```

- [ ] **Step 14: Run the full suite**

Run: `latte test`
Expected: PASS. Every pre-existing test still passes; `TokenStoreTest` and the two new assertions are green.

- [ ] **Step 15: Commit**

```bash
git add -A
git commit -m "feat: Move Handler tokens into a machine-managed tokens.json

Adds Tokens, TokenStore, and HandlerPaths.tokensFile, and replaces the
accessToken and refreshToken fields in handler.json with authURL. The
token file is written through an atomic move so an interrupted write
cannot truncate a working credential."
```

---

### Task 2: PKCE and auth configuration

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/auth/PKCE.java`
- Create: `src/main/java/dev/theagencyhq/handler/auth/AuthConfiguration.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/PKCETest.java` (create)
- Test: `src/test/java/dev/theagencyhq/handler/tests/AuthConfigurationTest.java` (create)

**Interfaces:**
- Consumes: `AuthenticationException` from Task 1.
- Produces:
  - `record PKCE(String verifier, String challenge)` with `static PKCE generate()`.
  - `class AuthConfiguration` with `AuthConfiguration(String issuer)`, `String authorizeURL(String state, String codeChallenge, String redirectURI)`, `String issuer()`, `URI tokenEndpoint()`, and constants `CLIENT_ID = "fa83bc7c-f1c5-48af-8ecb-6c09cf766d73"`, `DEFAULT_ISSUER = "https://auth.theagencyhq.dev"`, `SCOPES = "openid offline_access"`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/dev/theagencyhq/handler/tests/PKCETest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.auth.PKCE;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

public class PKCETest {
  @Test
  public void challengeIsTheBase64URLSHA256OfTheVerifier() throws NoSuchAlgorithmException {
    PKCE pkce = PKCE.generate();

    byte[] hash = MessageDigest.getInstance("SHA-256").digest(pkce.verifier().getBytes(StandardCharsets.US_ASCII));
    String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

    assertEquals(pkce.challenge(), expected);
  }

  @Test
  public void eachGenerationIsDistinct() {
    assertNotEquals(PKCE.generate().verifier(), PKCE.generate().verifier());
  }

  @Test
  public void theVerifierIsUnpaddedBase64URLWithinTheLengthRFC7636Requires() {
    String verifier = PKCE.generate().verifier();

    assertTrue(verifier.length() >= 43 && verifier.length() <= 128, "Verifier length was " + verifier.length());
    assertTrue(verifier.matches("[A-Za-z0-9\\-._~]+"), "Verifier was not base64url unreserved: " + verifier);
  }
}
```

Create `src/test/java/dev/theagencyhq/handler/tests/AuthConfigurationTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.auth.AuthConfiguration;
import dev.theagencyhq.handler.auth.AuthenticationException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class AuthConfigurationTest {
  @Test
  public void authorizeURLCarriesEveryParameterAndEncodesThem() {
    String url = new AuthConfiguration("http://localhost:9015")
        .authorizeURL("st ate", "chal+lenge", "http://127.0.0.1:54321/callback");

    assertTrue(url.startsWith("http://localhost:9015/oauth2/authorize?response_type=code"), "URL was: " + url);
    assertTrue(url.contains("&client_id=fa83bc7c-f1c5-48af-8ecb-6c09cf766d73"), "URL was: " + url);
    assertTrue(url.contains("&redirect_uri=http%3A%2F%2F127.0.0.1%3A54321%2Fcallback"), "URL was: " + url);
    assertTrue(url.contains("&scope=openid+offline_access"), "URL was: " + url);
    assertTrue(url.contains("&code_challenge=chal%2Blenge"), "URL was: " + url);
    assertTrue(url.contains("&code_challenge_method=S256"), "URL was: " + url);
    assertTrue(url.contains("&state=st+ate"), "URL was: " + url);
  }

  @Test(dataProvider = "invalidIssuers")
  public void invalidIssuersAreRejectedWithTheValueInTheMessage(String issuer) {
    AuthenticationException e = assertThrows(AuthenticationException.class, () -> new AuthConfiguration(issuer));
    assertTrue(e.getMessage().contains("[" + issuer + "]"), "Message was: " + e.getMessage());
  }

  @DataProvider
  public Object[][] invalidIssuers() {
    return new Object[][]{
        {"auth.theagencyhq.dev"},        // no scheme, so not absolute
        {"ftp://auth.theagencyhq.dev"},  // wrong scheme
        {"http://"},                     // no host
        {"not a url at all"}
    };
  }

  @Test
  public void issuerDefaultsWhenAbsentAndStripsEveryTrailingSlash() {
    assertEquals(new AuthConfiguration(null).issuer(), "https://auth.theagencyhq.dev");
    assertEquals(new AuthConfiguration("  ").issuer(), "https://auth.theagencyhq.dev");
    assertEquals(new AuthConfiguration("http://localhost:9015///").issuer(), "http://localhost:9015");
    assertEquals(new AuthConfiguration("  http://localhost:9015  ").issuer(), "http://localhost:9015");
  }

  @Test
  public void tokenEndpointHangsOffTheIssuer() {
    assertEquals(new AuthConfiguration("http://localhost:9015").tokenEndpoint(),
                 URI.create("http://localhost:9015/oauth2/token"));
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `latte test --test=dev.theagencyhq.handler.tests.AuthConfigurationTest`
Expected: compilation failure — `cannot find symbol: class AuthConfiguration`.

- [ ] **Step 3: Create `PKCE`**

Port from `/Users/bpontarelli/dev/latte-java/cli/src/main/java/org/lattejava/cli/auth/PKCE.java`, changing the package, the copyright header, the exception type to `AuthenticationException`, and the imports to `import module java.base;`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * A PKCE (RFC 7636) code verifier and its derived S256 code challenge. The verifier stays in memory for the life of a
 * single login and is never written anywhere.
 *
 * @author Brian Pontarelli
 */
public record PKCE(String verifier, String challenge) {
  public static PKCE generate() {
    byte[] randomBytes = new byte[32];
    new SecureRandom().nextBytes(randomBytes);
    String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
      return new PKCE(verifier, challenge);
    } catch (NoSuchAlgorithmException e) {
      throw new AuthenticationException("SHA-256 is not available in this JVM. Message was [" + e.getMessage() + "]", e);
    }
  }
}
```

- [ ] **Step 4: Create `AuthConfiguration`**

Port from the Latte CLI's `AuthConfiguration.java`, which has already been updated for ephemeral ports. Change the package, header, exception type, imports, and the two constants. The full class:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Holds the resolved OAuth issuer and the hardcoded public-client settings, and builds the OAuth endpoint URLs the
 * login flow uses.
 *
 * <p>The Handler is a public client — it ships as a jar on developers' machines, so it cannot hold a secret. PKCE is
 * what makes that safe, and the FusionAuth Application requires it.
 *
 * @author Brian Pontarelli
 */
public class AuthConfiguration {
  public static final String CLIENT_ID = "fa83bc7c-f1c5-48af-8ecb-6c09cf766d73";
  public static final String DEFAULT_ISSUER = "https://auth.theagencyhq.dev";
  public static final String SCOPES = "openid offline_access";

  private final String issuer;

  public AuthConfiguration(String issuer) {
    String resolved = (issuer == null || issuer.isBlank()) ? DEFAULT_ISSUER : issuer.trim();
    validate(resolved);
    while (resolved.endsWith("/")) {
      resolved = resolved.substring(0, resolved.length() - 1);
    }

    this.issuer = resolved;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static void validate(String issuer) {
    URI uri;
    try {
      uri = new URI(issuer);
    } catch (URISyntaxException e) {
      throw new AuthenticationException("Invalid issuer URL [" + issuer + "]. It must be an absolute http or https URL.", e);
    }

    String scheme = uri.getScheme();
    if (!uri.isAbsolute() || uri.getHost() == null || (!scheme.equals("http") && !scheme.equals("https"))) {
      throw new AuthenticationException("Invalid issuer URL [" + issuer + "]. It must be an absolute http or https URL.");
    }
  }

  /**
   * Builds the OAuth authorization request URL for the IdP login page.
   *
   * @param state         A random nonce echoed back on the redirect to defend against CSRF.
   * @param codeChallenge The base64url-encoded SHA-256 of the PKCE code verifier.
   * @param redirectURI   The loopback redirect URI, which carries the ephemeral port the loopback server bound.
   * @return The fully-formed authorize URL.
   */
  public String authorizeURL(String state, String codeChallenge, String redirectURI) {
    return issuer + "/oauth2/authorize?response_type=code" +
        "&client_id=" + encode(CLIENT_ID) +
        "&redirect_uri=" + encode(redirectURI) +
        "&scope=" + encode(SCOPES) +
        "&code_challenge=" + encode(codeChallenge) +
        "&code_challenge_method=S256" +
        "&state=" + encode(state);
  }

  public String issuer() {
    return issuer;
  }

  public URI tokenEndpoint() {
    return URI.create(issuer + "/oauth2/token");
  }
}
```

Note: `new URI("not a url at all")` throws `URISyntaxException` because of the spaces, and `new URI("auth.theagencyhq.dev")` parses but is not absolute — both paths are covered by `validate`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `latte test --test=dev.theagencyhq.handler.tests.AuthConfigurationTest`
then: `latte test --test=dev.theagencyhq.handler.tests.PKCETest`
Expected: PASS, 4 and 3 tests respectively.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: Add PKCE generation and OAuth issuer configuration"
```

---

### Task 3: The loopback callback server

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/auth/LoopbackServer.java`
- Create: `src/main/resources/auth/success.html`
- Create: `src/main/resources/auth/error.html`
- Modify: `src/main/java/module-info.java` — add `requires jdk.httpserver;`
- Test: `src/test/java/dev/theagencyhq/handler/tests/LoopbackServerTest.java` (create)

**Interfaces:**
- Consumes: `AuthenticationException` from Task 1.
- Produces: `class LoopbackServer` with `LoopbackServer(String expectedState)`, `void start()`, `int port()`, `String redirectURI()`, `String awaitCode(Duration timeout)`, `void stop()`, and constants `CALLBACK_PATH = "/callback"`, `LOOPBACK_HOST = "127.0.0.1"`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/LoopbackServerTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module java.net.http;
import module org.testng;

import dev.theagencyhq.handler.auth.AuthenticationException;
import dev.theagencyhq.handler.auth.LoopbackServer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class LoopbackServerTest {
  private LoopbackServer server;

  @Test
  public void anErrorParameterFailsTheWaitAndNamesTheError() throws Exception {
    start("expected");
    get(server.redirectURI() + "?error=access_denied");

    AuthenticationException e = assertThrows(AuthenticationException.class,
                                             () -> server.awaitCode(Duration.ofSeconds(5)));
    assertTrue(e.getMessage().contains("[access_denied]"), "Message was: " + e.getMessage());
  }

  @Test
  public void aMismatchedStateFailsTheWait() throws Exception {
    start("expected");
    get(server.redirectURI() + "?code=abc&state=tampered");

    AuthenticationException e = assertThrows(AuthenticationException.class,
                                             () -> server.awaitCode(Duration.ofSeconds(5)));
    assertTrue(e.getMessage().contains("state"), "Message was: " + e.getMessage());
  }

  @Test
  public void aMissingCodeFailsTheWait() throws Exception {
    start("expected");
    get(server.redirectURI() + "?state=expected");

    assertThrows(AuthenticationException.class, () -> server.awaitCode(Duration.ofSeconds(5)));
  }

  @Test
  public void aMatchingRedirectYieldsTheCodeAndServesAPage() throws Exception {
    start("expected");

    HttpResponse<String> response = get(server.redirectURI() + "?code=the-code&state=expected");

    assertEquals(response.statusCode(), 200);
    assertTrue(response.body().contains("<html"), "The browser must get a real page. Body was: " + response.body());
    assertEquals(server.awaitCode(Duration.ofSeconds(5)), "the-code");
  }

  @Test
  public void portIsUnavailableBeforeStart() {
    assertThrows(IllegalStateException.class, () -> new LoopbackServer("state").port());
  }

  @Test
  public void theTimeoutIsReportedInSeconds() throws Exception {
    start("expected");

    AuthenticationException e = assertThrows(AuthenticationException.class,
                                             () -> server.awaitCode(Duration.ofMillis(200)));
    assertTrue(e.getMessage().contains("Timed out"), "Message was: " + e.getMessage());
  }

  @Test
  public void twoServersBindDifferentEphemeralPortsOnTheLoopbackLiteral() {
    start("first");
    LoopbackServer second = new LoopbackServer("second");
    second.start();

    try {
      assertNotEquals(server.port(), second.port(), "Ephemeral ports must not collide");
      assertTrue(server.redirectURI().startsWith("http://127.0.0.1:"), "URI was: " + server.redirectURI());
      assertTrue(server.redirectURI().endsWith("/callback"), "URI was: " + server.redirectURI());
      assertEquals(server.redirectURI(), "http://127.0.0.1:" + server.port() + "/callback");
    } finally {
      second.stop();
    }
  }

  @AfterMethod
  public void tearDown() {
    if (server != null) {
      server.stop();
      server = null;
    }
  }

  private HttpResponse<String> get(String url) throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private void start(String state) {
    server = new LoopbackServer(state);
    server.start();
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.LoopbackServerTest`
Expected: compilation failure — `cannot find symbol: class LoopbackServer`.

- [ ] **Step 3: Add `jdk.httpserver` to the module**

In `src/main/java/module-info.java`, add `requires jdk.httpserver;` to the `requires` list, alphabetized (after `java.net.http;`, before `static org.lattejava.json;`).

- [ ] **Step 4: Create the result pages**

Create `src/main/resources/auth/success.html`. Fully self-contained — inline CSS only, no external stylesheet, font, or image, so it renders on a machine with no network. Theme it to The Agency's vocabulary. Keep it to roughly this shape and length:

```html
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Handler — signed in</title>
  <style>
    :root { color-scheme: light dark; }
    body { align-items: center; background: #0f1115; color: #e6e8eb; display: flex; font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; justify-content: center; margin: 0; min-height: 100vh; }
    .card { background: #171a21; border: 1px solid #262b36; border-radius: 12px; max-width: 26rem; padding: 2.5rem; text-align: center; }
    .mark { color: #4ade80; font-size: 2.5rem; line-height: 1; }
    h1 { font-size: 1.25rem; font-weight: 600; margin: 1rem 0 0.5rem; }
    p { color: #9aa3b2; font-size: 0.9rem; line-height: 1.6; margin: 0; }
    code { background: #0f1115; border-radius: 4px; color: #e6e8eb; padding: 0.1rem 0.35rem; }
  </style>
</head>
<body>
  <div class="card">
    <div class="mark">&#10003;</div>
    <h1>Credentials accepted</h1>
    <p>The Handler is authenticated and will start receiving Briefs. You can close this tab and return to your terminal.</p>
  </div>
</body>
</html>
```

Create `src/main/resources/auth/error.html` with the same structure and styling, `.mark` colored `#f87171`, the glyph `&#10005;`, the heading `Credentials rejected`, and the body text: `The login did not complete. Close this tab and check your terminal for the reason.`

- [ ] **Step 5: Create `LoopbackServer`**

Port from `/Users/bpontarelli/dev/latte-java/cli/src/main/java/org/lattejava/cli/auth/LoopbackServer.java`.

**Important:** that file has since been rewritten to use the Latte project's own `org.lattejava.http.server.HTTPServer`. **Do not follow it there.** The Handler uses the JDK's `com.sun.net.httpserver.HttpServer` from the `jdk.httpserver` module, because `org.lattejava:http` is a *test-scoped* dependency here (`project.latte`, group `test-compile`) and promoting it to compile scope would add a jar to the daemon's shipped bundle for the sake of one single-use callback listener. The approved design (§2) commits to adding no dependencies. Take the CLI's *structure and comments*, but keep the JDK server API:

- `HttpServer.create(new InetSocketAddress(LOOPBACK_HOST, 0), 0)` to bind, then `server.createContext(CALLBACK_PATH, this::handle)` and `server.start()`.
- `port()` reads `server.getAddress().getPort()`, and throws `IllegalStateException` naming the problem when `server` is null.
- `handle(HttpExchange)` parses the query itself with a private `parseQuery` helper — the JDK server hands over a raw query string. Because `createContext` routes only `/callback`, no 404 branch is needed.
- `stop()` calls `server.stop(0)`.

Other changes to make:

1. Package `dev.theagencyhq.handler.auth`, the Agency copyright header.
2. `RuntimeFailureException` → `AuthenticationException` everywhere.
3. Imports become `import module java.base;` plus `import com.sun.net.httpserver.HttpExchange;` and `import com.sun.net.httpserver.HttpServer;`.
4. Rewrite the `loadPage` Javadoc — the CLI's describes coffee-shop Latte branding that does not apply. Replace with: *Loads the page shown in the browser once the redirect lands. Both pages are entirely self-contained — inline styles, no external requests — so they render on a machine with no network. They live as resources in the jar.*
5. Keep the class Javadoc's explanation of why the port is ephemeral and why the host is the IPv4 literal — that is the reasoning this design turns on. Keep the `handle` comment about flushing the response before completing the future, and the `start` comment about port 0. Both document non-obvious ordering a future reader would otherwise undo.
6. Do **not** port the CLI's static block pinning the `org.lattejava.http` logger level. It exists only because the Latte HTTP server narrates at INFO, and the JDK server does not.

Order the members per the project convention: static fields, instance fields, constructor, then public methods alphabetically (`awaitCode`, `port`, `redirectURI`, `start`, `stop`), then private methods alphabetically (`handle`, `loadPage`, `parseQuery`).

- [ ] **Step 6: Run the test to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.LoopbackServerTest`
Expected: PASS, 7 tests.

If `loadPage` throws because the resource is not found, confirm the Latte build copies `src/main/resources` into the jar and that the module can read it. `LoopbackServer.class.getResourceAsStream("/auth/success.html")` works for a resource in the same module without any `opens` clause.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: Add the loopback OAuth callback server

Binds an ephemeral port on 127.0.0.1 so a login never collides with
another process, and derives the redirect URI from the bound port."
```

---

### Task 4: Reading the access token

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/auth/AccessTokenClaims.java`
- Create: `src/main/java/dev/theagencyhq/handler/auth/JWTs.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/JWTsTest.java` (create)

**Interfaces:**
- Consumes: `AuthenticationException` from Task 1.
- Produces:
  - `record AccessTokenClaims(String email)` with `static AccessTokenClaims fromJSON(byte[])`.
  - `final class JWTs` with `static AccessTokenClaims claims(String jwt)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/JWTsTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.auth.AuthenticationException;
import dev.theagencyhq.handler.auth.JWTs;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;

public class JWTsTest {
  @Test
  public void aPayloadWithoutAnEmailYieldsNull() {
    assertNull(JWTs.claims(jwt("{\"sub\":\"abc\"}")).email());
  }

  @Test
  public void theEmailClaimIsRead() {
    assertEquals(JWTs.claims(jwt("{\"email\":\"agent@theagencyhq.org\",\"sub\":\"abc\"}")).email(),
                 "agent@theagencyhq.org");
  }

  @Test
  public void unknownClaimsAreIgnored() {
    // A real access token carries a dozen claims the Handler does not model
    String payload = "{\"aud\":\"x\",\"email\":\"agent@theagencyhq.org\",\"exp\":1893456000,\"iss\":\"y\","
        + "\"roles\":[\"admin\"],\"scope\":\"openid offline_access\"}";

    assertEquals(JWTs.claims(jwt(payload)).email(), "agent@theagencyhq.org");
  }

  @Test(dataProvider = "malformed")
  public void malformedTokensAreRejected(String jwt) {
    assertThrows(AuthenticationException.class, () -> JWTs.claims(jwt));
  }

  @DataProvider
  public Object[][] malformed() {
    return new Object[][]{
        {"not-a-jwt"},                       // no segments at all
        {"onlyoneheader."},                  // fewer than two segments with content
        {"aGVhZGVy.!!!not-base64!!!.sig"},   // payload is not base64url
        {"aGVhZGVy." + encode("[1,2,3]") + ".sig"}  // payload is JSON, but not an object
    };
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String jwt(String payload) {
    return encode("{\"alg\":\"RS256\"}") + "." + encode(payload) + ".signature-not-verified";
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.JWTsTest`
Expected: compilation failure — `cannot find symbol: class JWTs`.

- [ ] **Step 3: Create `AccessTokenClaims`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.auth.internal.AccessTokenClaimsJSON;

/**
 * The claims the Handler reads off an access token. A real token carries many more; the processor is non-strict, so
 * everything not modeled here is ignored.
 *
 * @author Brian Pontarelli
 */
@JSON
public record AccessTokenClaims(String email) {
  public static AccessTokenClaims fromJSON(byte[] json) {
    return AccessTokenClaimsJSON.fromJSON(json);
  }
}
```

- [ ] **Step 4: Create `JWTs`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Reads the claims out of a JWT by base64url-decoding its payload segment. This does not verify the signature, and
 * that is deliberate: the token arrives directly from the IdP over TLS in the response to a request the Handler itself
 * made, so there is no untrusted party in between. The Handler never makes an authorization decision from these
 * claims — it reads the email to print a confirmation line. The Agency verifies the signature, because The Agency
 * receives the token from somewhere it does not control.
 *
 * @author Brian Pontarelli
 */
public final class JWTs {
  private JWTs() {
  }

  /**
   * @param jwt The encoded JWT.
   * @return The claims the Handler models.
   */
  public static AccessTokenClaims claims(String jwt) {
    String[] parts = jwt.split("\\.");
    if (parts.length < 2 || parts[1].isEmpty()) {
      throw new AuthenticationException("Malformed JWT. It did not have a payload segment.");
    }

    byte[] payload;
    try {
      payload = Base64.getUrlDecoder().decode(parts[1]);
    } catch (IllegalArgumentException e) {
      throw new AuthenticationException("Could not decode the JWT payload. Message was [" + e.getMessage() + "]", e);
    }

    try {
      return AccessTokenClaims.fromJSON(payload);
    } catch (RuntimeException e) {
      throw new AuthenticationException("Could not parse the JWT payload. Message was [" + e.getMessage() + "]", e);
    }
  }
}
```

Note the error messages deliberately do not interpolate the JWT — that would log a credential.

- [ ] **Step 5: Run it to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.JWTsTest`
Expected: PASS, 7 tests (3 plus the 4-row data provider).

If the `[1,2,3]` row fails because the generated `fromJSON` accepts a JSON array, change that data-provider row to `{"aGVhZGVy." + encode("\"a string\"") + ".sig"}` and re-run. If it still passes rather than throws, drop that row — the other three cover the malformed paths that matter.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: Read the email claim off the access token"
```

---

### Task 5: The token endpoint client

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/auth/TokenResponse.java`
- Create: `src/main/java/dev/theagencyhq/handler/auth/OAuthClient.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/FakeIdP.java` (create)
- Test: `src/test/java/dev/theagencyhq/handler/tests/OAuthClientTest.java` (create)

**Interfaces:**
- Consumes: `AuthConfiguration` (Task 2), `Tokens` and `AuthenticationException` (Task 1).
- Produces:
  - `record TokenResponse(String accessToken, String error, String errorDescription, String refreshToken)` with `static TokenResponse fromJSON(byte[])`.
  - `class OAuthClient` with `OAuthClient(AuthConfiguration)`, `Tokens exchangeCode(String code, String codeVerifier, String redirectURI)`, `Tokens refresh(String refreshToken)`.
  - `class FakeIdP implements Closeable` with `void script(int status, String body)`, `int start()`, `String url()`, `List<String> requestBodies()`, `List<String> paths()`.

- [ ] **Step 1: Write the fake IdP**

Create `src/test/java/dev/theagencyhq/handler/tests/FakeIdP.java`. `FakeAgency` cannot be reused: it only writes a body when the status is 200, and the token endpoint's error bodies matter here.

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.lattejava.http;

/**
 * A scriptable stand-in for the FusionAuth token endpoint, backed by a real HTTP server on an ephemeral port. Unlike
 * {@link FakeAgency} this returns a body for every status, because the OAuth error body is part of what the client
 * has to handle.
 *
 * @author Brian Pontarelli
 */
public class FakeIdP implements Closeable {
  private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
  private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
  private final Queue<Scripted> scripted = new ConcurrentLinkedQueue<>();
  private int port;
  private HTTPServer server;

  @Override
  public void close() {
    if (server != null) {
      server.close();
      server = null;
    }
  }

  public List<String> paths() {
    return List.copyOf(paths);
  }

  public List<String> requestBodies() {
    return List.copyOf(requestBodies);
  }

  /**
   * Queues one response. Responses are consumed in order; when the queue empties, every further request gets a 500
   * with an empty body.
   *
   * @param status The HTTP status to return.
   * @param body   The response body, returned for every status.
   */
  public void script(int status, String body) {
    scripted.add(new Scripted(status, body));
  }

  public int start() {
    HTTPHandler handler = (req, res) -> {
      paths.add(req.getPath());
      requestBodies.add(req.hasBody() ? new String(req.getBodyBytes(), StandardCharsets.UTF_8) : "");

      Scripted next = scripted.poll();
      int status = next == null ? 500 : next.status();
      String body = next == null ? "" : next.body();

      res.setStatus(status);
      if (body != null && !body.isEmpty()) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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

- [ ] **Step 2: Write the failing test**

Create `src/test/java/dev/theagencyhq/handler/tests/OAuthClientTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.auth.AuthConfiguration;
import dev.theagencyhq.handler.auth.AuthenticationException;
import dev.theagencyhq.handler.auth.OAuthClient;
import dev.theagencyhq.handler.auth.Tokens;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class OAuthClientTest {
  private FakeIdP idp;

  @Test
  public void anErrorStatusFailsWithTheStatusAndTheOAuthErrorBody() {
    idp.script(400, "{\"error\":\"invalid_grant\"}");

    AuthenticationException e = assertThrows(AuthenticationException.class,
                                             () -> client().refresh("stale-refresh-token"));

    assertTrue(e.getMessage().contains("[400]"), "Message was: " + e.getMessage());
    assertTrue(e.getMessage().contains("invalid_grant"), "Message was: " + e.getMessage());
    assertTrue(!e.getMessage().contains("stale-refresh-token"), "The message must never carry a token: " + e.getMessage());
  }

  @Test
  public void aResponseWithoutAnAccessTokenIsARejection() {
    idp.script(200, "{\"token_type\":\"Bearer\"}");

    assertThrows(AuthenticationException.class, () -> client().refresh("refresh-token"));
  }

  @Test
  public void exchangeCodePostsThePublicClientFormAndReturnsTheTokens() {
    idp.script(200, "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    Tokens tokens = client().exchangeCode("the-code", "the-verifier", "http://127.0.0.1:54321/callback");

    assertEquals(tokens.accessToken(), "at");
    assertEquals(tokens.refreshToken(), "rt");
    assertEquals(idp.paths(), List.of("/oauth2/token"));

    String body = idp.requestBodies().getFirst();
    assertTrue(body.contains("grant_type=authorization_code"), "Body was: " + body);
    assertTrue(body.contains("code=the-code"), "Body was: " + body);
    assertTrue(body.contains("code_verifier=the-verifier"), "Body was: " + body);
    assertTrue(body.contains("client_id=fa83bc7c-f1c5-48af-8ecb-6c09cf766d73"), "Body was: " + body);
    assertTrue(body.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A54321%2Fcallback"), "Body was: " + body);
    assertTrue(!body.contains("client_secret"), "A public client must send no secret. Body was: " + body);
  }

  @Test
  public void refreshPostsTheRefreshGrantAndSendsNoRedirectURI() {
    idp.script(200, "{\"access_token\":\"new-at\",\"refresh_token\":\"new-rt\"}");

    Tokens tokens = client().refresh("old-rt");

    assertEquals(tokens.accessToken(), "new-at");
    assertEquals(tokens.refreshToken(), "new-rt");

    String body = idp.requestBodies().getFirst();
    assertTrue(body.contains("grant_type=refresh_token"), "Body was: " + body);
    assertTrue(body.contains("refresh_token=old-rt"), "Body was: " + body);
    assertTrue(!body.contains("redirect_uri"), "The refresh grant sends no redirect URI. Body was: " + body);
  }

  @Test
  public void refreshKeepsTheExistingRefreshTokenWhenTheResponseOmitsOne() {
    // FusionAuth may or may not rotate the refresh token; dropping it would force an unnecessary re-login
    idp.script(200, "{\"access_token\":\"new-at\"}");

    assertEquals(client().refresh("old-rt").refreshToken(), "old-rt");
  }

  @BeforeMethod
  public void setUp() {
    idp = new FakeIdP();
    idp.start();
  }

  @AfterMethod
  public void tearDown() {
    idp.close();
  }

  private OAuthClient client() {
    return new OAuthClient(new AuthConfiguration(idp.url()));
  }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.OAuthClientTest`
Expected: compilation failure — `cannot find symbol: class OAuthClient`.

- [ ] **Step 4: Create `TokenResponse`**

`SNAKE_CASE` naming maps `accessToken` to `access_token` and `errorDescription` to `error_description` at compile time, so the wire names need no per-field annotation:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.auth.internal.TokenResponseJSON;

/**
 * The token endpoint's JSON body. The same shape covers success and failure — OAuth returns {@code error} and
 * {@code error_description} in place of the tokens — and everything else the endpoint sends back, such as
 * {@code expires_in} and {@code token_type}, is ignored.
 *
 * @author Brian Pontarelli
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record TokenResponse(String accessToken, String error, String errorDescription, String refreshToken) {
  public static TokenResponse fromJSON(byte[] json) {
    return TokenResponseJSON.fromJSON(json);
  }
}
```

- [ ] **Step 5: Create `OAuthClient`**

Both grants share one form-post path. `refresh` keeps the caller's refresh token when the response omits a new one:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;
import module java.net.http;

/**
 * Calls the IdP token endpoint as a public client — the PKCE code verifier stands in for a client secret, because a
 * jar on a developer's machine cannot hold one.
 *
 * @author Brian Pontarelli
 */
public class OAuthClient {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final AuthConfiguration configuration;

  public OAuthClient(AuthConfiguration configuration) {
    this.configuration = configuration;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * Exchanges an authorization code for tokens.
   *
   * @param code         The authorization code captured on the loopback redirect.
   * @param codeVerifier The PKCE code verifier matching the challenge sent on the authorize request.
   * @param redirectURI  The same redirect URI sent on the authorize request. The IdP requires the two to match
   *                     exactly, so it carries the ephemeral port the loopback server bound.
   * @return The tokens.
   */
  public Tokens exchangeCode(String code, String codeVerifier, String redirectURI) {
    String form = "grant_type=authorization_code" +
        "&code=" + encode(code) +
        "&redirect_uri=" + encode(redirectURI) +
        "&client_id=" + encode(AuthConfiguration.CLIENT_ID) +
        "&code_verifier=" + encode(codeVerifier);

    return post(form, null);
  }

  /**
   * Renews the access token with the refresh grant. This sends no redirect URI, which is what keeps renewal
   * independent of the loopback server and its ephemeral port.
   *
   * @param refreshToken The stored refresh token.
   * @return The new tokens, carrying the supplied refresh token when the IdP did not rotate it.
   */
  public Tokens refresh(String refreshToken) {
    String form = "grant_type=refresh_token" +
        "&refresh_token=" + encode(refreshToken) +
        "&client_id=" + encode(AuthConfiguration.CLIENT_ID);

    return post(form, refreshToken);
  }

  /**
   * @param form                 The URL-encoded request body.
   * @param existingRefreshToken Returned in place of an absent {@code refresh_token}, or null when there is none.
   * @return The tokens.
   */
  private Tokens post(String form, String existingRefreshToken) {
    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(configuration.tokenEndpoint())
                                     .header("Content-Type", "application/x-www-form-urlencoded")
                                     .header("Accept", "application/json")
                                     .timeout(REQUEST_TIMEOUT)
                                     .POST(HttpRequest.BodyPublishers.ofString(form))
                                     .build();

    HttpResponse<String> response;
    try (HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()) {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new AuthenticationException("The token request to [" + configuration.tokenEndpoint()
          + "] failed. Message was [" + e.getMessage() + "]", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AuthenticationException("The token request was interrupted.", e);
    }

    // The body here is an OAuth error object, never a credential, so it is safe to surface
    if (response.statusCode() != 200) {
      throw new AuthenticationException("The token request failed with status [" + response.statusCode()
          + "] and body [" + response.body() + "]");
    }

    TokenResponse parsed;
    try {
      parsed = TokenResponse.fromJSON(response.body().getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new AuthenticationException("The token response was not valid JSON. Message was [" + e.getMessage() + "]", e);
    }

    if (parsed.accessToken() == null || parsed.accessToken().isBlank()) {
      throw new AuthenticationException("The token response did not contain an access token. Error was ["
          + parsed.error() + "] and description was [" + parsed.errorDescription() + "]");
    }

    String refreshToken = parsed.refreshToken() == null || parsed.refreshToken().isBlank() ? existingRefreshToken
                                                                                          : parsed.refreshToken();
    return new Tokens(parsed.accessToken(), refreshToken);
  }
}
```

- [ ] **Step 6: Run it to verify it passes**

Run: `latte test --test=dev.theagencyhq.handler.tests.OAuthClientTest`
Expected: PASS, 5 tests.

If `NamingStrategy` does not resolve, check its import — it is in `org.lattejava.json` and comes in with `import module org.lattejava.json;`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: Add the OAuth token endpoint client

Covers the authorization code and refresh grants as a public client,
keeping the existing refresh token when the IdP does not rotate it."
```

---

### Task 6: Refresh behind `TokenSupplier`

**Files:**
- Modify: `src/main/java/dev/theagencyhq/handler/agency/TokenSupplier.java`
- Modify: `src/main/java/dev/theagencyhq/handler/agency/AgencyClient.java`
- Delete: `src/main/java/dev/theagencyhq/handler/agency/ConfigTokenSupplier.java`
- Create: `src/main/java/dev/theagencyhq/handler/auth/OAuthTokenSupplier.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/StubTokenSupplier.java` (create)
- Test: `src/test/java/dev/theagencyhq/handler/tests/OAuthTokenSupplierTest.java` (create)
- Test: `src/test/java/dev/theagencyhq/handler/tests/AgencyClientTest.java` (modify)
- Test: `ReceiveThreadTest.java:136` (modify — the `() -> "token"` lambda stops compiling)

**Interfaces:**
- Consumes: `OAuthClient` (Task 5), `TokenStore` and `Tokens` (Task 1).
- Produces:
  - `interface TokenSupplier { String bearerToken(); boolean refresh(); }`
  - `class OAuthTokenSupplier implements TokenSupplier` with `OAuthTokenSupplier(TokenStore store, OAuthClient client)`.
  - `class StubTokenSupplier implements TokenSupplier` with `StubTokenSupplier(String token)`, `StubTokenSupplier(String token, String tokenAfterRefresh)`, and `int refreshCount()`.

**Critical:** `TokenSupplier` is currently used as a lambda in three places. Adding a second abstract method breaks all of them. `StubTokenSupplier` replaces them.

- [ ] **Step 1: Write the stub**

Create `src/test/java/dev/theagencyhq/handler/tests/StubTokenSupplier.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import dev.theagencyhq.handler.agency.TokenSupplier;

/**
 * A {@link TokenSupplier} whose refresh outcome is scripted, so the retry contract can be tested without an IdP.
 *
 * @author Brian Pontarelli
 */
public class StubTokenSupplier implements TokenSupplier {
  private final String tokenAfterRefresh;
  private int refreshCount;
  private String token;

  /**
   * A supplier whose refresh always fails, standing in for having no refresh token or a rejected one.
   *
   * @param token The bearer token.
   */
  public StubTokenSupplier(String token) {
    this(token, null);
  }

  /**
   * @param token             The bearer token before any refresh.
   * @param tokenAfterRefresh The bearer token a successful refresh installs, or null to make refresh fail.
   */
  public StubTokenSupplier(String token, String tokenAfterRefresh) {
    this.token = token;
    this.tokenAfterRefresh = tokenAfterRefresh;
  }

  @Override
  public String bearerToken() {
    return token;
  }

  @Override
  public boolean refresh() {
    refreshCount++;
    if (tokenAfterRefresh == null) {
      return false;
    }

    token = tokenAfterRefresh;
    return true;
  }

  public int refreshCount() {
    return refreshCount;
  }
}
```

- [ ] **Step 2: Write the failing tests**

Add to `src/test/java/dev/theagencyhq/handler/tests/AgencyClientTest.java`. Also replace the two existing lambdas: `() -> "t"` at line 26 becomes `new StubTokenSupplier("t")`, and `() -> "test-token"` in the `client()` helper at line 123 becomes `new StubTokenSupplier("test-token")`.

```java
  @Test
  public void aRejectedTokenIsRetriedOnceWithTheRefreshedToken() throws IOException {
    agency.script(401, "");
    agency.script(200, Files.readString(Path.of("src/test/resources/agency/briefing-updated.json")));
    StubTokenSupplier tokens = new StubTokenSupplier("stale-token", "fresh-token");

    BriefingResult result = new AgencyClient(agency.url(), tokens).briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Updated, "Expected Updated but got " + result);
    Assert.assertEquals(tokens.refreshCount(), 1);
    Assert.assertEquals(agency.authorizationHeaders(), List.of("Bearer stale-token", "Bearer fresh-token"));
  }

  @Test
  public void aFailedRefreshMakesTheRejectionFatal() {
    agency.script(401, "");
    StubTokenSupplier tokens = new StubTokenSupplier("stale-token");

    BriefingResult result = new AgencyClient(agency.url(), tokens).briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Failed, "Expected Failed but got " + result);
    Assert.assertTrue(((BriefingResult.Failed) result).authenticationFailure());
    Assert.assertTrue(((BriefingResult.Failed) result).message().contains("handler login"),
                      "The developer needs to be told what to do: " + ((BriefingResult.Failed) result).message());
    Assert.assertEquals(tokens.refreshCount(), 1);
  }

  @Test
  public void theRetryIsNotItselfRetried() {
    // A refresh that yields a token the Agency also rejects must terminate, not loop
    agency.script(401, "");
    agency.script(401, "");
    StubTokenSupplier tokens = new StubTokenSupplier("stale-token", "also-rejected");

    BriefingResult result = new AgencyClient(agency.url(), tokens).briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Failed, "Expected Failed but got " + result);
    Assert.assertTrue(((BriefingResult.Failed) result).authenticationFailure());
    Assert.assertEquals(tokens.refreshCount(), 1, "Refresh must be attempted once, not once per 401");
    Assert.assertEquals(agency.authorizationHeaders().size(), 2, "Exactly two requests: the original and one retry");
  }
```

Check the accessor name on `BriefingResult.Failed` before writing these — read `src/main/java/dev/theagencyhq/handler/agency/BriefingResult.java` and use whatever the record actually calls its message and its authentication flag.

Create `src/test/java/dev/theagencyhq/handler/tests/OAuthTokenSupplierTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.auth.AuthConfiguration;
import dev.theagencyhq.handler.auth.OAuthClient;
import dev.theagencyhq.handler.auth.OAuthTokenSupplier;
import dev.theagencyhq.handler.auth.TokenStore;
import dev.theagencyhq.handler.auth.Tokens;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class OAuthTokenSupplierTest extends BaseTest {
  private FakeIdP idp;

  @Test
  public void aRefreshTokenIsExchangedAndThePairIsPersisted() {
    store().store(new Tokens("old-access", "old-refresh"));
    idp.script(200, "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\"}");
    OAuthTokenSupplier supplier = supplier();

    assertEquals(supplier.bearerToken(), "old-access");
    assertTrue(supplier.refresh());
    assertEquals(supplier.bearerToken(), "new-access");

    Tokens onDisk = store().load();
    assertEquals(onDisk.accessToken(), "new-access");
    assertEquals(onDisk.refreshToken(), "new-refresh");
  }

  @Test
  public void aRejectedRefreshReportsFailureRatherThanThrowing() {
    // The daemon must survive this: it becomes a fatal 401 telling the developer to log in again
    store().store(new Tokens("old-access", "revoked-refresh"));
    idp.script(400, "{\"error\":\"invalid_grant\"}");

    assertFalse(supplier().refresh());
  }

  @Test
  public void withNoRefreshTokenRefreshFailsWithoutCallingTheIdP() {
    store().store(new Tokens("access-only", null));

    assertFalse(supplier().refresh());
    assertEquals(idp.paths(), List.of(), "The IdP must not be called when there is nothing to exchange");
  }

  @Test
  public void aTokenWrittenByAnotherProcessIsAdoptedWithoutCallingTheIdP() {
    // This is what makes `handler login` take effect in a running daemon without a restart
    store().store(new Tokens("original", "refresh"));
    OAuthTokenSupplier supplier = supplier();
    assertEquals(supplier.bearerToken(), "original");

    store().store(new Tokens("written-by-login", "new-refresh"));

    assertTrue(supplier.refresh());
    assertEquals(supplier.bearerToken(), "written-by-login");
    assertEquals(idp.paths(), List.of(), "An adopted token must not cost an IdP round trip");
  }

  @Test
  public void anAbsentTokenFileIsNotAnAdoption() {
    assertFalse(supplier().refresh());
    assertEquals(idp.paths(), List.of());
  }

  @BeforeMethod
  public void setUp() {
    idp = new FakeIdP();
    idp.start();
  }

  @AfterMethod
  public void tearDown() {
    idp.close();
  }

  private TokenStore store() {
    return new TokenStore(base.resolve("config/tokens.json"));
  }

  private OAuthTokenSupplier supplier() {
    return new OAuthTokenSupplier(store(), new OAuthClient(new AuthConfiguration(idp.url())));
  }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `latte test --test=dev.theagencyhq.handler.tests.OAuthTokenSupplierTest`
Expected: compilation failure — `cannot find symbol: class OAuthTokenSupplier`.

- [ ] **Step 4: Add `refresh()` to `TokenSupplier`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

/**
 * Supplies the bearer token for Agency requests, and renews it when The Agency rejects one. Every mechanic of how a
 * token is obtained, stored, and renewed lives behind this interface — {@link AgencyClient} only ever learns whether
 * a better token is now available.
 *
 * @author Brian Pontarelli
 */
public interface TokenSupplier {
  String bearerToken();

  /**
   * Attempts to obtain a usable access token after the current one was rejected. Implementations must not throw: an
   * IdP that is unreachable is a reason to report failure, not a reason to end the receive cycle.
   *
   * @return Whether an access token different from the rejected one is now available.
   */
  boolean refresh();
}
```

- [ ] **Step 5: Add the retry to `AgencyClient`**

Extract the request construction into a private `send` so it can be rebuilt with a refreshed token, and retry exactly once on a `401`. `send` returns a sealed `Attempt` carrying either the response or the already-formed failure, because the failure message differs by cause and a bare null could not express that.

Replace the whole of `briefing` and add the two private members below it:

```java
  public BriefingResult briefing(List<CurrentVersion> currentVersions) {
    byte[] body;
    try {
      body = new BriefingRequest(currentVersions).toJSONBytes();
    } catch (RuntimeException e) {
      return new BriefingResult.Failed("Unable to build the briefing request: " + e.getMessage(), false);
    }

    HttpResponse<byte[]> response;
    switch (send(body)) {
      case Attempt.Failure failure -> {
        return failure.result();
      }
      case Attempt.Response ok -> response = ok.response();
    }

    // One retry, only on 401, and only when the supplier says it has something better. The retry's own 401 is
    // terminal — a refreshed token the Agency still rejects means re-authentication, not another loop.
    if (response.statusCode() == 401) {
      if (!tokens.refresh()) {
        return new BriefingResult.Failed("The Agency rejected the access token. Run [handler login].", true);
      }

      switch (send(body)) {
        case Attempt.Failure failure -> {
          return failure.result();
        }
        case Attempt.Response ok -> response = ok.response();
      }

      if (response.statusCode() == 401) {
        return new BriefingResult.Failed("The Agency rejected the refreshed access token. Run [handler login].", true);
      }
    }

    return switch (response.statusCode()) {
      case 200 -> parse(response.body());
      case 304 -> new BriefingResult.NotModified();
      case 403 -> new BriefingResult.Forbidden();
      default -> new BriefingResult.Failed("The Agency returned status [" + response.statusCode() + "]", false);
    };
  }
```

```java
  /**
   * Sends one briefing request with whatever bearer token the supplier currently holds.
   *
   * @param body The serialized request body, built once and reused across the retry.
   * @return The response, or the failure it should be reported as.
   */
  private Attempt send(byte[] body) {
    HttpRequest request;
    try {
      request = HttpRequest.newBuilder(URI.create(theAgencyURL + BRIEFING_PATH))
                           .header("Authorization", "Bearer " + tokens.bearerToken())
                           .header("Content-Type", "application/json")
                           .timeout(REQUEST_TIMEOUT)
                           .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                           .build();
    } catch (RuntimeException e) {
      return new Attempt.Failure(new BriefingResult.Failed("Unable to build the briefing request: "
          + e.getMessage(), false));
    }

    try {
      return new Attempt.Response(httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Attempt.Failure(new BriefingResult.Failed("The briefing request was interrupted", false));
    } catch (IOException e) {
      return new Attempt.Failure(new BriefingResult.Failed("The Agency at [" + theAgencyURL + "] is unreachable: "
          + e.getMessage(), false));
    }
  }

  private sealed interface Attempt {
    record Failure(BriefingResult result) implements Attempt {
    }

    record Response(HttpResponse<byte[]> response) implements Attempt {
    }
  }
```

Keep the class's contract intact: **`briefing` never throws.** If the `switch` pattern over the sealed interface fights the compiler, an `instanceof` chain is equally acceptable — the shape of the control flow is what matters, not the syntax.

- [ ] **Step 6: Create `OAuthTokenSupplier`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

import dev.theagencyhq.handler.agency.TokenSupplier;

/**
 * Serves the stored access token and renews it with the refresh grant when The Agency rejects one.
 *
 * <p>The token is read from disk once and cached, so the per-request path does no file I/O. {@link #refresh()} is the
 * only thing that goes back to disk, and it starts by checking whether another process — a {@code handler login} the
 * developer just ran — already replaced the token. That check is what lets a login take effect in a running daemon
 * without a restart.
 *
 * @author Brian Pontarelli
 */
public class OAuthTokenSupplier implements TokenSupplier {
  private static final System.Logger LOG = System.getLogger(OAuthTokenSupplier.class.getName());

  private final OAuthClient client;
  private final TokenStore store;
  private Tokens tokens;

  public OAuthTokenSupplier(TokenStore store, OAuthClient client) {
    this.store = store;
    this.client = client;
  }

  @Override
  public synchronized String bearerToken() {
    return cached().accessToken();
  }

  @Override
  public synchronized boolean refresh() {
    Tokens onDisk = store.load();

    // Another process may have logged in since this token was cached. Adopting costs nothing and skips the IdP.
    if (onDisk.present() && !onDisk.accessToken().equals(cached().accessToken())) {
      LOG.log(System.Logger.Level.DEBUG, "Adopted an access token written by another process");
      tokens = onDisk;
      return true;
    }

    tokens = onDisk;
    if (tokens.refreshToken().isEmpty()) {
      LOG.log(System.Logger.Level.WARNING, "There is no refresh token stored. Run [handler login].");
      return false;
    }

    try {
      Tokens refreshed = client.refresh(tokens.refreshToken());
      store.store(refreshed);
      tokens = refreshed;
      return true;
    } catch (AuthenticationException e) {
      // Never fatal here: the caller turns this into a 401 that tells the developer to log in again
      LOG.log(System.Logger.Level.WARNING, "Unable to refresh the access token. Message was [{0}]", e.getMessage());
      return false;
    }
  }

  private Tokens cached() {
    if (tokens == null) {
      tokens = store.load();
    }

    return tokens;
  }
}
```

- [ ] **Step 7: Delete `ConfigTokenSupplier` and patch `Main`**

Delete `src/main/java/dev/theagencyhq/handler/agency/ConfigTokenSupplier.java`. In `Main.java`, replace `new ConfigTokenSupplier(config)` with:

```java
      TokenStore tokenStore = new TokenStore(paths.tokensFile());
      AuthConfiguration authConfiguration = new AuthConfiguration(config.authURL());
      OAuthTokenSupplier tokens = new OAuthTokenSupplier(tokenStore, new OAuthClient(authConfiguration));
      AgencyClient agency = new AgencyClient(config.theAgencyURL(), tokens);
```

Add the four `dev.theagencyhq.handler.auth.*` imports, alphabetized within the existing import block.

- [ ] **Step 8: Fix the last lambda**

`ReceiveThreadTest.java:136` uses `() -> "token"`. Replace with `new StubTokenSupplier("token")`.

- [ ] **Step 9: Run the full suite**

Run: `latte test`
Expected: PASS. Search the output for any remaining lambda compilation error against `TokenSupplier` and fix it the same way.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: Renew the access token with the refresh grant

TokenSupplier gains refresh(), which AgencyClient calls once on a 401
before retrying. OAuthTokenSupplier adopts a token another process
wrote before spending an IdP round trip, so logging in takes effect in
a running daemon without a restart."
```

---

### Task 7: The login flow and the CLI

**Files:**
- Create: `src/main/java/dev/theagencyhq/handler/auth/Browser.java`
- Create: `src/main/java/dev/theagencyhq/handler/auth/Browsers.java`
- Create: `src/main/java/dev/theagencyhq/handler/auth/Login.java`
- Modify: `src/main/java/dev/theagencyhq/handler/cli/HandlerCLI.java`
- Modify: `src/main/java/dev/theagencyhq/handler/Main.java`
- Test: `src/test/java/dev/theagencyhq/handler/tests/HandlerCLITest.java` (modify)

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces:
  - `interface Browser { void open(String url, PrintStream out); }`
  - `final class Browsers` with `static void open(String url, PrintStream out)`.
  - `class Login` with `Login(AuthConfiguration configuration, OAuthClient client, TokenStore store, Browser browser)` and `String run(PrintStream out)` returning the email, or null when the token carries none.
  - `HandlerCLI` constructor gains `Login login` and `TokenStore tokenStore` parameters; `run` handles `login` and `logout`.

- [ ] **Step 1: Write the failing test**

Add to `HandlerCLITest.java`. Read the file's existing `cli()` helper first and thread the two new constructor arguments through it.

```java
  @Test
  public void logoutClearsTheTokenFileAndIsIdempotent() throws IOException {
    tokenStore().store(new Tokens("access", "refresh"));

    assertEquals(cli().run("logout"), 0);
    assertFalse(Files.exists(tokensFile()));
    assertTrue(output.toString().contains("Logged out"), "Output was: " + output);

    output.reset();
    assertEquals(cli().run("logout"), 0, "Logging out twice is not an error");
    assertTrue(output.toString().contains("Not logged in"), "Output was: " + output);
  }

  @Test
  public void statusReportsTokenPresenceFromTheTokenStoreAndNeverPrintsIt() throws IOException {
    assertEquals(cli().run("status"), 0);
    assertTrue(output.toString().contains("accessToken  absent"), "Output was: " + output);

    output.reset();
    tokenStore().store(new Tokens("super-secret-token", "refresh"));

    assertEquals(cli().run("status"), 0);
    String printed = output.toString();
    assertTrue(printed.contains("accessToken  present"), "Output was: " + printed);
    assertFalse(printed.contains("super-secret-token"), "The token must never be printed. Output was: " + printed);
  }

  @Test
  public void helpNamesLoginAndLogout() {
    assertEquals(cli().run("help"), 0);
    assertTrue(output.toString().contains("login"), "Output was: " + output);
    assertTrue(output.toString().contains("logout"), "Output was: " + output);
  }

  private Path tokensFile() {
    return base.resolve("config/tokens.json");
  }

  private TokenStore tokenStore() {
    return new TokenStore(tokensFile());
  }
```

The existing `cli()` helper builds a `HandlerPaths`. Point its `tokensFile` component at `tokensFile()` so `status` and `logout` read the same file the test writes.

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=dev.theagencyhq.handler.tests.HandlerCLITest`
Expected: compilation failure — `cannot find symbol: class TokenStore` in the test, or a constructor arity error.

- [ ] **Step 3: Create `Browser` and `Browsers`**

`Browser` takes a `PrintStream` because that is what `HandlerCLI` already carries for output — this project has no `Output` abstraction like the Latte CLI's.

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Opens a URL for the developer to complete the interactive part of the login. Production launches the system web
 * browser; tests substitute an implementation that drives the login over HTTP.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface Browser {
  void open(String url, PrintStream out);
}
```

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Opens the developer's default web browser to a URL. The URL is printed first, so a machine where no browser can be
 * launched — over SSH, for example — still gives the developer something to work with.
 *
 * @author Brian Pontarelli
 */
public final class Browsers {
  private Browsers() {
  }

  public static void open(String url, PrintStream out) {
    out.println("Opening your browser to log in. If it does not open automatically, visit:");
    out.println(url);

    // Shell out rather than use java.awt.Desktop. On macOS, touching AWT turns this into a GUI application: it gets a
    // Dock icon and appears in the Cmd-Tab switcher. It would also drag java.desktop into a daemon's module graph.
    String[] command = browserCommand(url);
    if (command == null) {
      return;
    }

    try {
      new ProcessBuilder(command).start();
    } catch (IOException e) {
      // The printed URL above is the fallback
    }
  }

  private static String[] browserCommand(String url) {
    String os = System.getProperty("os.name").toLowerCase();
    if (os.contains("mac")) {
      return new String[]{"open", url};
    } else if (os.contains("nix") || os.contains("nux")) {
      return new String[]{"xdg-open", url};
    }

    return null;
  }
}
```

- [ ] **Step 4: Create `Login`**

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Runs the OAuth 2.0 Authorization Code flow with PKCE and stores the resulting tokens.
 *
 * @author Brian Pontarelli
 */
public class Login {
  private static final Duration BROWSER_TIMEOUT = Duration.ofMinutes(2);

  private final Browser browser;
  private final OAuthClient client;
  private final AuthConfiguration configuration;
  private final TokenStore store;

  public Login(AuthConfiguration configuration, OAuthClient client, TokenStore store, Browser browser) {
    this.configuration = configuration;
    this.client = client;
    this.store = store;
    this.browser = browser;
  }

  private static String randomState() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Runs the flow to completion, writing the tokens on success.
   *
   * @param out Where the browser instructions are printed.
   * @return The email on the access token, or null when it carries none.
   */
  public String run(PrintStream out) {
    PKCE pkce = PKCE.generate();
    String state = randomState();

    LoopbackServer server = new LoopbackServer(state);
    server.start();

    // The OS picks the port at bind time, so the redirect URI is only knowable after start(). The same URI has to go
    // out on both the authorize request and the token request, so capture it once here.
    String redirectURI = server.redirectURI();

    String code;
    try {
      browser.open(configuration.authorizeURL(state, pkce.challenge(), redirectURI), out);
      code = server.awaitCode(BROWSER_TIMEOUT);
    } finally {
      server.stop();
    }

    Tokens tokens = client.exchangeCode(code, pkce.verifier(), redirectURI);
    store.store(tokens);

    return JWTs.claims(tokens.accessToken()).email();
  }
}
```

- [ ] **Step 5: Wire the CLI**

In `HandlerCLI`:

1. Add `private final Login login;` and `private final TokenStore tokenStore;` to the instance fields, keeping them alphabetized (`applier, config, handler, login, out, paths, planner, scanner, store, tokenStore`).
2. Add both to the constructor parameter list and assign them.
3. Add two arms to the `run` switch, placed to keep the switch reading in the order `usage` lists them:

```java
      case "login" -> login();
      case "logout" -> logout();
```

4. Add the two private methods, alphabetized among the existing private methods (`describe`, `login`, `logout`, `planFor`, `status`, `sync`, `usage`):

```java
  private int login() {
    try {
      String email = login.run(out);
      out.println(email == null ? "Login successful." : "Logged in as [" + email + "]");
      return 0;
    } catch (AuthenticationException e) {
      out.println(e.getMessage());
      return 1;
    }
  }

  private int logout() {
    out.println(tokenStore.clear() ? "Logged out." : "Not logged in.");
    return 0;
  }
```

5. In `status()`, replace `config.accessToken().isEmpty()` with the token store:

```java
    out.println("accessToken  " + (tokenStore.load().present() ? "present" : "absent"));
```

and add a `tokensFile` line beside the existing `configFile` line:

```java
    out.println("tokensFile   " + paths.tokensFile());
```

6. Extend `usage()`:

```java
        Usage: handler [command]
        
          daemon             Run the receive and distribute loops in the foreground (default)
          sync [--force]     Run one receive pass then one distribute pass, then exit
          status             Print resolved paths, stored Organizations, and every Location's state
          login              Log in to The Agency through your browser
          logout             Discard the stored tokens
          help               Print this message
          --version          Print the version
```

7. Add `import dev.theagencyhq.handler.auth.*;` to the import block, alphabetized among the other `dev.theagencyhq.handler.*` imports.

- [ ] **Step 6: Wire `Main`**

Extend the block Task 6 added so the `Login` and `TokenStore` reach `HandlerCLI`:

```java
      TokenStore tokenStore = new TokenStore(paths.tokensFile());
      AuthConfiguration authConfiguration = new AuthConfiguration(config.authURL());
      OAuthClient oauthClient = new OAuthClient(authConfiguration);
      OAuthTokenSupplier tokens = new OAuthTokenSupplier(tokenStore, oauthClient);
      Login login = new Login(authConfiguration, oauthClient, tokenStore, Browsers::open);
      AgencyClient agency = new AgencyClient(config.theAgencyURL(), tokens);
```

and pass `login` and `tokenStore` into the `HandlerCLI` constructor in the position they were declared.

- [ ] **Step 7: Run the full suite**

Run: `latte test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: Add handler login and handler logout

Login runs the authorization code flow with PKCE through the developer's
browser and stores the tokens. Status now reports token presence from
the token store and still never prints the token itself."
```

---

### Task 8: Local FusionAuth and the end-to-end test

**Files:**
- Create: `src/test/fusionauth/.env`
- Create: `src/test/fusionauth/docker-compose.yml`
- Create: `src/test/fusionauth/kickstart/kickstart.json`
- Create: `src/test/fusionauth/plugins/.gitkeep`
- Create: `src/test/java/dev/theagencyhq/handler/tests/FusionAuthBrowser.java`
- Create: `src/test/java/dev/theagencyhq/handler/tests/LoginTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `Login`, `Browser`, `TokenStore`, `OAuthTokenSupplier`, `AuthConfiguration`, `OAuthClient` from earlier tasks.

**Reference material:** the Latte CLI's equivalents are at `/Users/bpontarelli/dev/latte-java/cli/src/test/fusionauth/` and `/Users/bpontarelli/dev/latte-java/cli/src/test/java/org/lattejava/cli/command/LoginCommandTest.java`. Port from them.

- [ ] **Step 1: Create the Compose stack**

Create `src/test/fusionauth/.env` verbatim:

```
DATABASE_USER=fusionauth
DATABASE_PASSWORD=hkaLBM3RVnyYeYeqE3WI1w2e4Avpy0Wd5O3s3
FUSIONAUTH_APP_KICKSTART_FILE=/usr/local/fusionauth/kickstart/kickstart.json
FUSIONAUTH_APP_MEMORY=512M
FUSIONAUTH_APP_RUNTIME_MODE=development
FUSIONAUTH_LOCAL_KICKSTART_DIRECTORY=./kickstart
FUSIONAUTH_LOCAL_PLUGIN_DIRECTORY=./plugins
FUSIONAUTH_SEARCH_TYPE=elasticsearch
OPENSEARCH_JAVA_OPTS="-Xms512m -Xmx512m"
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
```

Create `src/test/fusionauth/docker-compose.yml` by copying `/Users/bpontarelli/dev/latte-java/cli/src/test/fusionauth/docker-compose.yml` and then **deleting** the optional services and their volumes: `caddy`, `cleanspeak`, `kafka`, `mailcatcher`, `opentelemetry`, `prometheus`, `alertmanager`, `zookeeper`, and the `caddy_config`, `caddy_data`, `cs_config`, `prometheus_data` volume entries. Keep `db`, `fusionauth`, and `search`, and the `db_data`, `fusionauth_config`, `search_data` volumes. Change the first line to:

```yaml
name: fusionauth-the-agency-hq
```

Create `src/test/fusionauth/plugins/.gitkeep` as an empty file — the Compose file bind-mounts that directory, so it has to exist.

- [ ] **Step 2: Create the Kickstart**

Create `src/test/fusionauth/kickstart/kickstart.json`. The application id must equal `AuthConfiguration.CLIENT_ID`:

```json
{
  "variables": {
    "adminEmail": "admin@theagencyhq.org",
    "adminPassword": "password",
    "adminUserId": "#{UUID()}",
    "agentEmail": "agent@theagencyhq.org",
    "agentPassword": "password",
    "agentUserId": "#{UUID()}",
    "apiKey": "33052c8a-c283-4e96-9d2a-eb1215c69f8f-not-for-prod",
    "asymmetricKeyId": "#{UUID()}",
    "defaultTenantId": "d7d09513-a3f5-401c-9685-34ab6c552453",
    "handlerApplicationId": "fa83bc7c-f1c5-48af-8ecb-6c09cf766d73",
    "accessTokenPopulateLambdaId": "#{UUID()}"
  },
  "apiKeys": [
    {
      "key": "#{apiKey}",
      "description": "Unrestricted API key"
    }
  ],
  "requests": [
    {
      "method": "POST",
      "url": "/api/key/generate/#{asymmetricKeyId}",
      "tenantId": "#{defaultTenantId}",
      "body": {
        "key": {
          "algorithm": "RS256",
          "name": "For The Agency",
          "length": 2048
        }
      }
    },
    {
      "method": "PATCH",
      "url": "/api/tenant/#{defaultTenantId}",
      "body": {
        "tenant": {
          "issuer": "http://localhost:9015"
        }
      }
    },
    {
      "method": "POST",
      "url": "/api/lambda/#{accessTokenPopulateLambdaId}",
      "body": {
        "lambda": {
          "name": "[Handler] Access token populate",
          "type": "JWTPopulate",
          "engineType": "GraalJS",
          "body": "function populate(jwt, user, registration) {\n  jwt.preferred_username = user.username;\n  jwt.email = user.email;\n}"
        }
      }
    },
    {
      "method": "POST",
      "url": "/api/application/#{handlerApplicationId}",
      "tenantId": "#{defaultTenantId}",
      "body": {
        "application": {
          "name": "The Agency Handler",
          "oauthConfiguration": {
            "authorizedRedirectURLs": [
              "http://127.0.0.1:*/callback"
            ],
            "authorizedURLValidationPolicy": "AllowWildcards",
            "clientAuthenticationPolicy": "NotRequired",
            "proofKeyForCodeExchangePolicy": "Required",
            "consentMode": "NeverPrompt",
            "enabledGrants": [
              "authorization_code",
              "refresh_token"
            ],
            "generateRefreshTokens": true,
            "requireRegistration": true
          },
          "jwtConfiguration": {
            "enabled": true,
            "accessTokenKeyId": "#{asymmetricKeyId}",
            "idTokenKeyId": "#{asymmetricKeyId}"
          },
          "lambdaConfiguration": {
            "accessTokenPopulateId": "#{accessTokenPopulateLambdaId}"
          }
        }
      }
    },
    {
      "method": "POST",
      "url": "/api/user/registration/#{adminUserId}",
      "body": {
        "registration": {
          "applicationId": "#{FUSIONAUTH_APPLICATION_ID}",
          "roles": [
            "admin"
          ]
        },
        "skipRegistrationVerification": true,
        "user": {
          "email": "#{adminEmail}",
          "username": "AdminUser",
          "password": "#{adminPassword}"
        }
      }
    },
    {
      "method": "POST",
      "url": "/api/user/registration/#{agentUserId}",
      "body": {
        "registration": {
          "applicationId": "#{handlerApplicationId}"
        },
        "skipRegistrationVerification": true,
        "user": {
          "email": "#{agentEmail}",
          "username": "Agent",
          "password": "#{agentPassword}"
        }
      }
    },
    {
      "method": "POST",
      "url": "/api/user/registration/#{adminUserId}",
      "body": {
        "registration": {
          "applicationId": "#{handlerApplicationId}"
        }
      }
    }
  ]
}
```

- [ ] **Step 3: Port the headless browser**

Create `src/test/java/dev/theagencyhq/handler/tests/FusionAuthBrowser.java` by porting the `FusionAuthBrowser` inner class from `/Users/bpontarelli/dev/latte-java/cli/src/test/java/org/lattejava/cli/command/LoginCommandTest.java` into a top-level class. Changes: the Agency copyright header, package `dev.theagencyhq.handler.tests`, implement `dev.theagencyhq.handler.auth.Browser`, and change the `open` signature's second parameter from `Output` to `PrintStream` (the parameter is unused in the body). Keep the class Javadoc explaining that it fetches the authorize page, submits the login form with the hidden fields carried forward, and follows the redirect chain to the loopback callback.

- [ ] **Step 4: Write the end-to-end test**

Create `src/test/java/dev/theagencyhq/handler/tests/LoginTest.java`:

```java
/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module java.net.http;
import module org.testng;

import dev.theagencyhq.handler.auth.AuthConfiguration;
import dev.theagencyhq.handler.auth.JWTs;
import dev.theagencyhq.handler.auth.Login;
import dev.theagencyhq.handler.auth.OAuthClient;
import dev.theagencyhq.handler.auth.OAuthTokenSupplier;
import dev.theagencyhq.handler.auth.TokenStore;
import dev.theagencyhq.handler.auth.Tokens;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

/**
 * Runs the real login flow against a local FusionAuth. The interactive browser step is driven over HTTP by
 * {@link FusionAuthBrowser} rather than opening a window, and the tokens land in a scratch directory rather than the
 * developer's real configuration.
 *
 * @author Brian Pontarelli
 */
public class LoginTest extends BaseTest {
  private static final String AGENT_EMAIL = "agent@theagencyhq.org";
  private static final String AGENT_PASSWORD = "password";
  private static final String ISSUER = "http://localhost:9015";

  @BeforeClass
  public void beforeClass() {
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      HttpResponse<String> response = client.send(
          HttpRequest.newBuilder(URI.create(ISSUER + "/api/status")).GET().timeout(Duration.ofSeconds(2)).build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new RuntimeException("FusionAuth returned HTTP [" + response.statusCode() + "]");
      }
    } catch (Exception e) {
      throw new RuntimeException("""
          FusionAuth is not running on http://localhost:9015. Start it with:
          
            cd src/test/fusionauth && docker compose up -d
          """, e);
    }
  }

  @Test
  public void loginStoresTokensAndTheAccessTokenCarriesTheEmail() {
    String email = login().run(new PrintStream(OutputStream.nullOutputStream()));

    assertEquals(email, AGENT_EMAIL);

    Tokens stored = store().load();
    assertTrue(stored.present(), "An access token should have been stored");
    assertTrue(!stored.refreshToken().isEmpty(), "A refresh token should have been stored");
    assertEquals(JWTs.claims(stored.accessToken()).email(), AGENT_EMAIL);
  }

  @Test
  public void twoLoginsInOneProcessBothSucceed() {
    // The ephemeral port is what makes this work; a fixed port would risk TIME_WAIT on the second bind
    assertEquals(login().run(new PrintStream(OutputStream.nullOutputStream())), AGENT_EMAIL);
    assertEquals(login().run(new PrintStream(OutputStream.nullOutputStream())), AGENT_EMAIL);

    assertTrue(store().load().present());
  }

  @Test
  public void aStoredRefreshTokenBuysANewAccessToken() {
    login().run(new PrintStream(OutputStream.nullOutputStream()));
    String original = store().load().accessToken();

    OAuthTokenSupplier supplier = new OAuthTokenSupplier(store(), new OAuthClient(new AuthConfiguration(ISSUER)));
    assertEquals(supplier.bearerToken(), original);
    assertTrue(supplier.refresh(), "The refresh grant should have been accepted");

    assertNotEquals(supplier.bearerToken(), original, "Refresh should have installed a new access token");
    assertEquals(store().load().accessToken(), supplier.bearerToken(), "The new token should have been persisted");
    assertEquals(JWTs.claims(supplier.bearerToken()).email(), AGENT_EMAIL);
  }

  private Login login() {
    AuthConfiguration configuration = new AuthConfiguration(ISSUER);
    return new Login(configuration, new OAuthClient(configuration), store(),
                     new FusionAuthBrowser(AGENT_EMAIL, AGENT_PASSWORD));
  }

  private TokenStore store() {
    return new TokenStore(base.resolve("config/tokens.json"));
  }
}
```

- [ ] **Step 5: Recreate FusionAuth from this repository's Kickstart**

The container already running was provisioned from a different project's Kickstart and has no Handler Application. Recreate it from these files:

```bash
cd src/test/fusionauth && docker compose down -v && docker compose up -d
```

Then poll until it answers, which takes 30–90 seconds on first boot while Kickstart runs:

```bash
until curl -sf http://localhost:9015/api/status > /dev/null; do sleep 5; done && echo ready
```

- [ ] **Step 6: Run the test**

Run: `latte test --test=dev.theagencyhq.handler.tests.LoginTest`
Expected: PASS, 3 tests.

If the redirect URI is rejected, confirm the Application shows `authorizedURLValidationPolicy` as `AllowWildcards` in the FusionAuth admin UI at `http://localhost:9015` (`admin@theagencyhq.org` / `password`), and that Kickstart actually ran — Kickstart is skipped entirely if the database already has data, which is why Step 5 passes `-v`.

- [ ] **Step 7: Update the README**

Add a section documenting how to run the login tests. Read the existing `README.md` first and match its heading level and tone:

```markdown
## Local FusionAuth

`handler login` authenticates against FusionAuth. The tests in `LoginTest` run the real flow against a local
instance, so they need one running:

    cd src/test/fusionauth && docker compose up -d

It comes up on `http://localhost:9015` with the Handler Application, an admin (`admin@theagencyhq.org` /
`password`), and a test user (`agent@theagencyhq.org` / `password`) already provisioned by Kickstart. Kickstart
only runs against an empty database, so re-provisioning after changing `kickstart.json` needs
`docker compose down -v` first.

Point the Handler at it by setting `authURL` in `handler.json`:

    "authURL": "http://localhost:9015"
```

- [ ] **Step 8: Run the full suite**

Run: `latte test`
Expected: PASS, every test including the FusionAuth-dependent ones.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "test: Add local FusionAuth and the end-to-end login test

Compose brings up FusionAuth on 9015 with a Kickstart that provisions
the Handler Application, its wildcard loopback redirect URL, and a test
user. LoginTest drives the real flow headlessly."
```

---

## Final verification

- [ ] Run `latte clean && latte test` and confirm the whole suite passes from a clean build.
- [ ] Run `git log --oneline main..HEAD` and confirm every commit is on `feat/fusionauth-login` and none on `main`.
- [ ] Grep for leftovers: `grep -rn "ConfigTokenSupplier\|accessToken()\|refreshToken()" src/main/java --include="*.java"` should show only `Tokens`, `TokenStore`, `OAuthTokenSupplier`, and `OAuthClient` — no reference to the deleted `HandlerConfig` accessors.
- [ ] Confirm no test or source file logs or prints a token: `grep -rn "accessToken\|refreshToken" src/main/java | grep -i "println\|LOG.log"` should return nothing.

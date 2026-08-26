# Handler — Login with FusionAuth Design

Status: proposed
Date: 2026-08-04
Builds on: `2026-07-26-handler-core-sync-design.md` (§7.1 `TokenSupplier`, §6.2 the config file, §10 CLI)

## 1. Purpose

The Handler authenticates to The Agency with a bearer token. Today that token is a string a developer pastes
into `handler.json` by hand, and when it expires the Handler logs an error every cycle until someone pastes a
new one.

This spec replaces that with the OAuth 2.0 Authorization Code flow with PKCE against FusionAuth, adds
`handler login` and `handler logout`, and renews the access token automatically using the refresh grant. It
also brings a local FusionAuth (Docker Compose plus Kickstart) into the repository so the flow can be developed
and tested end to end.

The core sync design listed exactly this as out of scope and reserved the `TokenSupplier` seam for it. This
spec fills that seam.

## 2. Scope

**In scope**

- A new `dev.theagencyhq.handler.auth` package holding the OAuth flow, adapted from the Latte CLI's
  `org.lattejava.cli.auth`
- `handler login` — browser-based authorization code exchange, tokens written to disk
- `handler logout` — discards the stored tokens
- Refresh-grant renewal, driven by a new `TokenSupplier.refresh()` that `AgencyClient` calls on a `401`
- Moving the tokens out of `handler.json` into a machine-managed `tokens.json`
- A local FusionAuth in `src/test/fusionauth/` with a Kickstart that provisions the Handler Application and a
  test user
- Tests: an end-to-end login against the real FusionAuth, plus unit coverage of every piece that does not need
  it

**Out of scope**

- How The Agency validates the JWT it receives. The Handler is a bearer-token client; what the server does with
  the token is the Agency's spec.
- The OAuth device flow. The Handler runs on the developer's own machine, where a loopback redirect works. A
  headless install would need the device flow; nothing in this design forecloses adding it later behind the same
  `TokenSupplier`.
- OS keychain storage. Tokens get the same `0600` treatment `handler.json` already gets.
- `handler init`.
- Windows, per the platform decision in the core sync design.

**Dependencies**: two changes. `org.lattejava:jwt` is added at compile scope for decoding the access token (§10);
it has no runtime dependencies of its own. `org.lattejava:http` moves from test scope to compile scope, because
the loopback callback server (§5.1) is built on it rather than on the JDK's `com.sun.net.httpserver` — the
project prefers its own HTTP server, and the cost is one jar in the shipped bundle. Otherwise the flow uses
`java.net.http` and `org.lattejava:json` at compile time for the wire records, both already present.

## 3. Decisions made during design

| # | Question                                          | Decision                                                                                                                                                                                                    |
|---|---------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | How much of auth lands in this spec?              | Login, logout, and refresh together. A daemon that runs for days is the one client that genuinely cannot get by without refresh — the Latte CLI could, because it exits in seconds.                          |
| 2 | Where does the local FusionAuth live?             | `handler/src/test/fusionauth/`, mirroring the Latte CLI. Self-contained: the Handler's tests never depend on a sibling repository being checked out.                                                         |
| 3 | Where do the issuer and client id come from?      | Hardcoded production defaults, overridable by an `authURL` field in `handler.json`. The daemon needs the issuer at refresh time, long after `login` exited, so it has to be resolvable from config alone.     |
| 4 | What triggers a refresh?                          | `AgencyClient` calls `TokenSupplier.refresh()` on a `401` and retries once. All of the refresh mechanics live behind the interface; `AgencyClient` only learns whether it now has a better token.             |
| 5 | Where are tokens stored?                          | A separate `tokens.json`, re-read on demand rather than snapshotted at startup. `handler.json` stays a hand-edited config file; tokens are machine-managed state and should not share a file with it.        |
| 6 | What does `latte test` do without FusionAuth?     | Fails, with the `docker compose up -d` command in the message. Same as the Latte CLI. A suite that silently skips its only end-to-end auth coverage is worse than one that tells you to start a container.   |
| 7 | Keep the AWT browser fallback from the CLI?       | No. Dropping it keeps `java.desktop` out of a daemon's module graph; the native `open`/`xdg-open` command covers every supported platform, and the URL is printed before either is attempted.                |
| 8 | Callback port                                     | An OS-assigned ephemeral port, per RFC 8252 §7.3, with a port wildcard in the FusionAuth Application's authorized redirect URLs. See §5.1 — there is no standard port, and a fixed one is a liability.        |

## 4. Component map

New package, alongside the existing ones:

```
dev/theagencyhq/handler/
├── agency/     AgencyClient, TokenSupplier, ...          (TokenSupplier gains refresh(); ConfigTokenSupplier is deleted)
├── auth/       AccessTokens, AuthConfiguration, Browser, Browsers, Login,
│               LoopbackServer, OAuthClient, OAuthTokenSupplier, PKCE, TokenResponse, Tokens, TokenStore
├── cli/        HandlerCLI                                 (gains login and logout)
├── config/     HandlerConfig, HandlerPaths, ConfigLoader  (config gains authURL, loses the tokens; paths gains tokensFile)
└── ...
```

| Class                | Responsibility                                                                                                  |
|----------------------|-----------------------------------------------------------------------------------------------------------------|
| `AccessTokens`       | Decodes an access token with `org.lattejava:jwt` and validates its claims. Does not verify the signature — see §10. |
| `AuthConfiguration`  | Resolves and validates the issuer; holds the public-client constants; builds the authorize URL, redirect URI, and token endpoint |
| `Browser`            | Functional interface — open a URL. The seam tests use to drive the login without a window.                        |
| `Browsers`           | Production implementation. Prints the URL, then launches the platform's browser command.                          |
| `Login`              | Orchestrates the flow of §6 end to end and returns the email it logged in as.                                     |
| `LoopbackServer`     | Single-use loopback HTTP server on an ephemeral port. Validates `state`, captures `code`, serves the result page. |
| `OAuthClient`        | The two token-endpoint calls: `exchangeCode` (which takes the redirect URI) and `refresh` (which does not).       |
| `OAuthTokenSupplier` | The `TokenSupplier` implementation. Caches the tokens, performs the refresh grant, persists the result.           |
| `PKCE`               | A verifier and its S256 challenge.                                                                                |
| `TokenResponse`      | The token endpoint's JSON body.                                                                                   |
| `Tokens`             | `(accessToken, refreshToken)` — both the in-memory pair and the on-disk shape.                                     |
| `TokenStore`         | Reads and writes `tokens.json`.                                                                                   |

There is no `LoginCommand` class. The Handler's CLI is a `switch` in `HandlerCLI`, and `login` and `logout` are
two more arms of it, consistent with `sync` and `status`. `HandlerCLI` gains two constructor parameters — a
`Login` and a `TokenStore` — which is what keeps the flow itself testable in isolation: `LoginTest` constructs a
`Login` with the headless `Browser` directly, without standing up the rest of the object graph.

## 5. Auth configuration

```java
public class AuthConfiguration {
  public static final String CLIENT_ID = "fa83bc7c-f1c5-48af-8ecb-6c09cf766d73";
  public static final String DEFAULT_ISSUER = "https://auth.theagencyhq.dev";
  public static final String SCOPES = "openid offline_access";

  public String authorizeURL(String state, String codeChallenge, String redirectURI) { … }

  public URI tokenEndpoint() { … }
}
```

There is no callback port or redirect URI constant here. The **`LoopbackServer` owns the redirect URI**, because
it owns the binding that determines the port:

```java
public class LoopbackServer {
  public static final String CALLBACK_HOST = "127.0.0.1";
  public static final String CALLBACK_PATH = "/callback";

  public LoopbackServer(String expectedState) { … }

  public void start() { … }           // binds CALLBACK_HOST:0

  public String redirectURI() { … }   // only meaningful after start()
}
```

Putting `redirectURI()` on the server rather than passing a port around is what makes it impossible for the URI
the Handler advertises and the address it is actually listening on to drift apart — see §5.1.

The Handler is a **public client**: it ships as a jar on developers' machines, so it cannot hold a secret. The
FusionAuth Application is configured `clientAuthenticationPolicy: NotRequired` with
`proofKeyForCodeExchangePolicy: Required`, which is what makes that safe — a stolen authorization code is
useless without the code verifier, and no secret exists to leak.

`CLIENT_ID` is a fixed UUID, the same value in `AuthConfiguration` and in the Kickstart file, so the local
FusionAuth and production agree on the Application's identity.

The issuer is resolved from `HandlerConfig.authURL()`, normalized in the compact constructor exactly the way
`theAgencyURL` already is — blank falls back to `DEFAULT_ISSUER`, otherwise trimmed with a trailing slash
stripped. `AuthConfiguration` then validates it is an absolute `http` or `https` URL with a host, and fails with
a message naming the bad value. Local development sets:

```json
"authURL": "http://localhost:9015"
```

### 5.1 The callback port

**There is no standard port for this.** RFC 8252 §7.3 requires the authorization server to go the other way:

> the authorization server MUST allow any port to be specified at the time of the request for loopback IP
> redirect URIs, to accommodate clients that obtain an available ephemeral port from the operating system at
> the time of the request

The ecosystem is split on whether implementers honor that. The AWS CLI does — `aws sso login` binds a random
free port and sends `http://127.0.0.1:{port}/oauth/callback`. Cloudflare's wrangler does not; it pins
`localhost:8976`, and that fixed port is the subject of a long tail of bug reports from anyone running it in a
container or on a remote development box. The Handler follows the RFC and the AWS CLI.

So `LoopbackServer` binds port `0` and lets the kernel choose. The resulting URI is needed in three places, and
they must agree exactly or the exchange fails: the `redirect_uri` on the authorize URL, the address the server
is listening on, and the `redirect_uri` sent again with the code exchange (OAuth requires it to match). Rather
than pass a port to two collaborators and trust them to rebuild the same string, `Login` captures
`server.redirectURI()` once after `start()` and hands that one value to both `authorizeURL` and
`exchangeCode`.

The **refresh grant does not send a `redirect_uri`**, so `OAuthTokenSupplier` never needs a port. Refresh stays
entirely independent of the loopback machinery, which is what lets the daemon renew a token hours after the
browser flow is over.

**The host is the IP literal `127.0.0.1`, not `localhost`.** RFC 8252 §7.3 recommends the literal, and the
reason is concrete: `localhost` resolves to `127.0.0.1` or `::1` depending on the machine, and Java's
`HttpServer` binds exactly one of them. If the browser picks the other, the redirect hits a closed port and the
login hangs until it times out. This is a real bug in wrangler, not a hypothetical. Using the literal on both
ends removes name resolution from the path entirely.

**This requires two settings on the FusionAuth Application.** Since 1.43.0, FusionAuth supports a full wildcard
in the port portion of an authorized redirect URL, gated on the Application's URL validation policy. Partial
wildcards are not supported (`:*` is valid, `:88*` is not). The Kickstart sets both:

```json
"authorizedRedirectURLs": ["http://127.0.0.1:*/callback"],
"authorizedURLValidationPolicy": "AllowWildcards"
```

A wildcard port against an IP-literal host has been **verified working** against FusionAuth. The documentation's
note that wildcards cannot be used with IP addresses constrains wildcards in the *host* segment, not the port,
so `127.0.0.1` needs no substitution and the RFC's preferred literal is what ships.

The trade-off in relaxing redirect URI validation is small and bounded: it applies to one Application, the
wildcard is confined to the port of a loopback address, and PKCE is what actually protects the authorization
code. An attacker positioned to bind a loopback port on the developer's machine already has local code
execution, which is a strictly worse problem than this one.

The Latte CLI has made this same change, so the ephemeral-port code in `org.lattejava.cli.auth` is a direct port
rather than an adaptation.

## 6. The login flow

`handler login`:

1. Build the `AuthConfiguration` from `config.authURL()`.
2. Generate a PKCE pair (32 random bytes, base64url, SHA-256 challenge) and 16 random bytes of `state`.
3. Start the `LoopbackServer` on `127.0.0.1:0` with one context, `/callback`, then capture
   `server.redirectURI()`. Steps 4 and 6 both use that exact string.
4. Open the browser to
   `{issuer}/oauth2/authorize?response_type=code&client_id=…&redirect_uri=…&scope=openid%20offline_access&code_challenge=…&code_challenge_method=S256&state=…`.
5. Wait up to two minutes for the redirect. The server rejects a response carrying `error`, a mismatched
   `state`, or no `code`; on each it serves the error page and fails the wait with a message saying which.
6. Exchange the code at `{issuer}/oauth2/token` with `grant_type=authorization_code`, the `client_id`, the
   *same* `redirect_uri` sent in step 4, and the `code_verifier`. No client secret.
7. Write `tokens.json`.
8. Read the `email` claim from the access token and print `Logged in as [email]`, or `Login successful.` when
   the claim is absent.

The server is stopped in a `finally`, so a timeout or a failed exchange never leaves a listener bound. Because
the port is ephemeral, a leaked listener would not even block the next attempt — but leaking one is still a bug,
and the `finally` is what keeps step 5's timeout path from becoming one.

Two details carried over from the Latte CLI because they are not obvious and were learned the hard way:

- **The result page is written and flushed before the code future completes.** Completing the future unblocks
  the main thread, which immediately stops the server; if that happened first, the server would tear down with
  the response still in flight and the browser would render a broken page.
- **The browser is launched with the platform command, not `java.awt.Desktop`.** On macOS, touching AWT turns a
  CLI into a GUI application — it gets a Dock icon and shows up in the app switcher.

Logging in while already logged in simply runs the flow again and overwrites. There is no prompt; re-running
`login` is the documented fix for a broken token, and asking "are you sure" for the recovery path is friction in
exactly the wrong place.

## 7. The refresh contract

`TokenSupplier` grows one method:

```java
public interface TokenSupplier {
  String bearerToken();

  /**
   * @return Whether a usable access token, different from the one that was just rejected, is now available.
   */
  boolean refresh();
}
```

`AgencyClient.briefing` changes only in its `401` arm:

```
401 → tokens.refresh()
        true  → rebuild the request with the new bearer token, send once more
                (that request's own 401 is terminal)
        false → Failed("The Agency rejected the access token. Run [handler login].", fatal = true)
```

One retry, only on `401`. Every other status behaves exactly as it does today, and `AgencyClient` still never
throws — an unavailable IdP is just another reason the briefing failed, and distribution keeps running from the
store.

`bearerToken()` reads `tokens.json` on first use and serves the cached access token from then on. `refresh()` is
the only thing that goes back to disk, which is what keeps the per-request path free of file I/O.

`OAuthTokenSupplier.refresh()`:

1. Re-read `tokens.json`. If it holds a non-empty access token that differs from the cached one, some other
   process — a `handler login` the developer just ran — already replaced it. Adopt it and return `true` without
   troubling the IdP. An absent or empty file is not an adoption; fall through.
2. Otherwise, if there is no refresh token, return `false`.
3. `POST {issuer}/oauth2/token` with `grant_type=refresh_token`, the refresh token, and the `client_id`.
4. On `200`, store the new tokens and return `true`. FusionAuth may or may not rotate the refresh token; when
   the response omits one, the existing refresh token is kept rather than dropped.
5. On anything else, log a warning with the status and the OAuth error body, and return `false`.

Step 1 is what makes "log in again while the daemon is running" work without a restart, and it is the reason
tokens live in their own file (§8).

On its own, step 1 only runs when The Agency answers `401`, which is once per receive interval — up to five minutes
during which the tray still says "logged out" after a successful login. `TokenWatcher` closes that gap: the daemon
registers a `WatchService` on the token file's directory, and on any event for `tokens.json` calls
`OAuthTokenSupplier.adoptFromDisk()`, which re-reads the file and adopts whatever it holds, present or absent. When
that changed the cached tokens — a login or a logout by another process — the receive thread is nudged, and its
cycle re-reports the credential state to the tray within seconds. When it did not — the daemon's own refresh just
wrote the file — nothing happens, so a refresh never costs a receive cycle. A `WatchService` rather than a socket or
a signal because it needs no contract with the writer: `login`, `logout`, and anything that ever writes the file in
future are all seen the same way, and a watcher that fails to start degrades to exactly the step-1 behaviour.

`refresh()` is `synchronized`. Only the receive thread talks to The Agency today, so contention is theoretical
— but `handler sync` and the daemon share this object graph, and a mutex around a network call that happens
once an hour costs nothing.

## 8. Token storage

New file, beside `handler.json` under the same XDG config base:

```
$XDG_CONFIG_HOME/the-agency-hq/tokens.json     (0600)
```

```json
{
  "accessToken": "eyJ…",
  "refreshToken": "…"
}
```

`HandlerPaths` gains a fourth component, `tokensFile()`, resolved in `HandlerPaths.resolve` alongside
`configFile`. Every test already injects `HandlerPaths`, so nothing gets to touch a real home directory.

`TokenStore` writes through a sibling temp file, restricts it to `rw-------`, and then atomically moves it into
place, falling back to a plain move where the filesystem cannot do an atomic one. A crashed or interrupted write
therefore never truncates a working token file. Reading an absent file yields empty tokens rather than an error
— "not logged in" is a normal state, not a failure.

**`handler.json` changes.** `accessToken` and `refreshToken` are removed and `authURL` is added. An existing
config file still carrying the old fields parses fine — the JSON processor is non-strict and ignores unknown
keys — so there is nothing to migrate and no version stamp to bump. In every real installation that field held a
hand-pasted placeholder anyway, and `handler login` supersedes it in one command.

`handler status` reads token presence from the `TokenStore` instead of the config, and continues to print
`present`/`absent` rather than any part of the token.

## 9. The browser result pages

Two self-contained HTML documents in `src/main/resources/auth/`, `success.html` and `error.html`, served by the
loopback server. Inline CSS and inline SVG only — no external stylesheet, font, or image — so they render
identically on a machine with no network. Both are themed to the product's vocabulary (Handler, Agency,
Location) and the success page's only real job is to tell the developer to go back to the terminal.

They are static resources with no interpolation, which is the whole reason they can be flat files: nothing about
the login result varies except which of the two is sent.

## 10. Reading the access token

`AccessTokens.decode` decodes the token with `org.lattejava:jwt` and validates its claims. Decoding is the
library's job rather than this project's: a hand-rolled base64url-and-parse is easy to get subtly wrong, and
the library already handles segment counting, input-size caps, alphabet validity, and the `NumericDate`
coercions that registered claims require.

**The signature is deliberately not verified.** The token arrives directly from the IdP over TLS in the
response to a request the Handler itself made, so there is no untrusted party in between to defend against.
Verifying would also put the IdP's JWKS endpoint on the path of every login and every refresh, turning a
transient IdP blip into a failed login — a poor trade for a check that guards against nothing in this
position. The Agency verifies the signature, because The Agency receives the token from somewhere it does not
control.

**The claims are validated**, which is a different matter: it costs no network call and no keys, and it catches
a token that is real but wrong for this Handler.

| Claim | Check | What it catches |
|-------|-------|-----------------|
| `iss`  | equals the configured issuer                | A token minted by a different FusionAuth — most often a developer whose `authURL` points somewhere other than the tenant that issued the token they are holding. |
| `aud`  | contains `AuthConfiguration.CLIENT_ID`      | A token issued for a different Application in the same tenant. Presenting one to The Agency would fail server-side; failing here says why. |
| `exp`  | not in the past, 60s of clock skew allowed  | A stale token read from `tokens.json`, which — unlike a freshly exchanged one — did not come straight from the IdP. |
| `nbf`  | not in the future, same skew                | The same, at the other end of the validity window. |

`JWTDecoder.decodeUnsecured` skips the decoder's own time-claim checks along with the signature check, so `exp`
and `nbf` are validated here explicitly rather than inherited. The 60-second skew covers ordinary drift between
the developer's machine and the IdP; without it a correctly-issued token can be rejected on a laptop whose clock
is a few seconds fast.

Every failure raises `AuthenticationException` with a message naming the mismatch — and never the token.

## 11. Local FusionAuth

```
src/test/fusionauth/
├── .env
├── docker-compose.yml
├── kickstart/kickstart.json
└── plugins/.gitkeep
```

Started with `cd src/test/fusionauth && docker compose up -d`, reachable at `http://localhost:9015`.

**Compose** is the standard FusionAuth stack — Postgres 16, OpenSearch 2.11, `fusionauth/fusionauth-app` — with
the compose project name `fusionauth-the-agency-hq`. The optional profiles the Latte CLI's file carries (Caddy,
Kafka, Cleanspeak, Mailcatcher, Prometheus, OpenTelemetry, Alertmanager) are dropped. The Handler needs none of
them, and every one of them is a service a developer has to read past to understand what is actually running.

**Kickstart** provisions, on first boot:

- An unrestricted API key and an RS256 asymmetric signing key
- The default tenant's issuer set to `http://localhost:9015`
- A `JWTPopulate` lambda that puts `email` and `preferred_username` on the access token. FusionAuth does not
  include `email` by default, and `handler login` prints it.
- The **Handler** Application, its id fixed to `AuthConfiguration.CLIENT_ID`, with
  `authorizedRedirectURLs: ["http://127.0.0.1:*/callback"]` and
  `authorizedURLValidationPolicy: "AllowWildcards"` (see §5.1) — the second is required or the first is rejected
  — plus PKCE required, client authentication not required,
  `enabledGrants: [authorization_code, refresh_token]`, `generateRefreshTokens: true`, and
  `consentMode: NeverPrompt`, the last so the headless test browser does not have to navigate a consent screen.
- An admin user registered to the FusionAuth admin application
- A test user, `agent@theagencyhq.org` / `password`, registered to the Handler Application

Default token lifetimes are left alone. No test needs an access token to actually expire — the refresh test
calls `refresh()` directly, and the `401`-and-retry test uses the fake Agency, which returns whatever status the
test asks for.

The README gains the start command and a note that the login tests require it.

## 12. Testing

The tests that need FusionAuth probe `GET /api/status` in `@BeforeClass` and throw with the `docker compose up
-d` command when it does not answer, so `latte test` fails loudly rather than quietly skipping its only
end-to-end auth coverage. The probe then asks the authorize endpoint about the Handler's client id, because
every FusionAuth answers `/api/status` — including an unrelated one already holding port 9015. An instance
without the Handler Application answers `invalid_client`, which gets its own message telling the developer to
stop the other container.

| Test                          | Covers                                                                                                                       | Needs FusionAuth |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------------|------------------|
| `LoginTest`                   | The real flow end to end: authorize, form login, redirect, code exchange, `tokens.json` contents, the `email` claim            | yes              |
| `RefreshTest`                 | Log in, then `OAuthTokenSupplier.refresh()` against the real token endpoint returns a different access token                    | yes              |
| `AuthConfigurationTest`       | Issuer normalization and rejection, authorize URL construction and encoding, redirect URI for a given port                      | no               |
| `AgencyClientTest` (extended) | `401` → `refresh()` true → retry succeeds; `401` → `refresh()` false → fatal `Failed`; the retry's own `401` is terminal        | no               |
| `HandlerCLITest` (extended)   | `logout` removes `tokens.json`; `logout` with nothing stored still exits `0`; `status` reports presence from the token store    | no               |
| `AccessTokensTest`            | Claim extraction; a wrong `iss`, a wrong `aud`, an expired `exp`, a future `nbf`, each rejected; the skew boundary; malformed and truncated tokens | no               |
| `LoopbackServerTest`          | Binds an ephemeral port and reports it; mismatched `state`, an `error` parameter, a missing `code`, the timeout, a browser that disconnects mid-response, and a request for another path being a 404 that leaves the login waiting | no               |
| `LoginTest` (ephemeral port)  | Two `Login` runs in the same JVM both succeed, which a fixed port could not guarantee                                          | yes              |
| `PKCETest`                    | Verifier shape and that the challenge is the base64url SHA-256 of the verifier                                                 | no               |
| `TokenStoreTest`              | Round trip, `0600` permissions, absent file, and that a failed write leaves the previous file intact                           | no               |

`FusionAuthBrowser` — the `Browser` implementation that completes the login over HTTP by scraping the hosted
login page's form, carrying its hidden fields forward, and following the redirect chain to the loopback callback
— is ported from the Latte CLI's test. It is what makes an end-to-end OAuth test unattended.

The `401` retry tests use the existing `FakeAgency` with a stub `TokenSupplier`, so the whole refresh contract is
verified against real HTTP and real status codes without any container.

## 13. Build changes

- `project.latte` — add `org.lattejava:jwt` to the `compile` group, and move `org.lattejava:http` from
  `test-compile` to `compile`, since the loopback server now runs on it.
- `module-info.java` (main) — add `requires org.lattejava.http;` for the loopback server and
  `requires org.lattejava.jwt;` for the token decoder, plus `exports dev.theagencyhq.handler.auth;`. Both
  `requires` and `exports` lists stay alphabetized. Neither library is `requires transitive`: each module that
  uses one declares it, including the test module, which builds its own tokens and fake servers.
- `Logging` — pin the `org.lattejava.http` logger to `WARNING`. The server narrates its startup at `INFO`, and
  one of those lines reports the configured port `0` rather than the ephemeral port actually bound, so it is
  actively misleading in the middle of a login.
- `Main` — build the `AuthConfiguration`, `TokenStore`, `OAuthClient`, and `Login`, then wire an
  `OAuthTokenSupplier` where `ConfigTokenSupplier` is wired today and pass the `Login` and `TokenStore` into
  `HandlerCLI`. `ConfigTokenSupplier` is deleted; it existed only as the placeholder this spec replaces.
  `Main` remains the only place that assembles the object graph.

## 14. CLI surface

```
handler login              Log in to The Agency through your browser
handler logout             Discard the stored tokens
```

`logout` deletes `tokens.json` and prints `Logged out.`, or prints `Not logged in.` when there was nothing
stored. Either way it exits `0` — logging out of a session you do not have is not an error.

### 14.1 The daemon preflight

`handler daemon` verifies its credentials before it commits to running, in this order. Either failure prints a
message and exits `1`:

1. **A credential is stored.** `tokens.json` holds both an access token and a refresh token. Without the refresh
   token there is nothing to renew with, so the daemon would run until its first `401` and then be stuck.
2. **The IdP still honors it.** The Handler spends the refresh token for a new access token and requires at
   minimum an access token back, then persists what it gets.

Step 2 is a real round trip rather than a local decode, because a stored access token can look perfectly valid
and still be dead — refresh token revoked, user removed from the Organization, Application reconfigured. Decoding
cannot see any of that. It also means the daemon begins life holding a token minted seconds ago rather than one
of unknown age.

**This is deliberately fatal, and that is a real trade.** §16 promises distribution keeps working from the store
when a token is rejected mid-flight, and the preflight does not honor that promise at startup: a Handler that
cannot verify its credentials does not start, so it does not distribute either. The reason is that the failure
mode it replaces is worse. A daemon that starts without a working credential looks healthy to launchd, logs a
successful start, and receives nothing — the developer finds out hours later from a Location that never updated,
rather than immediately from a message. Startup is the one moment when a human is watching.

The two failures print different advice, because they call for different actions. A rejected credential says run
`handler login`; an unreachable issuer says check the network. Telling a developer to log in while their
connection is down sends them to a browser that cannot load the page either, so `OAuthClient` raises
`IssuerUnreachableException` for a transport failure and `AuthenticationException` for everything the IdP
actually answered.

`handler sync` gets no preflight. It is a one-shot command whose output the developer is already reading, and a
`401` there reports itself.

### 14.2 What `status` checks

`status` gains an `introspect` line that asks the IdP, through RFC 7662 token introspection, what it makes of the
stored access token — then applies §10's claim rules to the response.

This is the only check in the Handler that catches a **revoked** token. A revoked token decodes cleanly, carries
valid claims, and is indistinguishable locally from a good one; only the IdP knows it is dead. `AccessTokens`
therefore exposes `validate(Introspection)` alongside `decode(String)`, so both paths share one definition of
what a valid claim set is.

FusionAuth does not advertise `introspection_endpoint` in its OpenID configuration, but `/oauth2/introspect`
accepts a public client — `client_id` with no secret — which is what makes this available to the Handler at all.

The line never throws. `status` is the command a developer runs when something is already wrong, so every
failure comes back as text:

```
introspect   valid — agent@theagencyhq.dev, expires 2026-08-06T13:13:27Z
introspect   invalid — The identity provider reports the access token is not active. …
introspect   unknown — Could not reach [http://localhost:9015/oauth2/introspect]. …
```

`unknown` is its own verdict rather than a failure: an unreachable issuer says nothing about whether the token is
good.

## 15. Security

- **Tokens are never logged, at any level.** This extends the existing rule to the refresh path: a failed
  refresh logs the HTTP status and the OAuth error body, which by definition carries an `error` code and no
  credential.
- **PKCE S256 is required by the Application configuration**, not merely offered by the client, so an
  intercepted authorization code cannot be redeemed.
- **`state` is 128 bits of `SecureRandom`** and is compared on the redirect; a mismatch fails the login rather
  than proceeding.
- **The loopback server binds `127.0.0.1`** and nothing else, serves exactly one path, handles one request, and
  is stopped in a `finally`.
- **The port wildcard is scoped to one Application and one loopback host.** It permits any port on
  `127.0.0.1`, which is what RFC 8252 §7.3 requires an authorization server to allow. An attacker who can bind
  a loopback port on the developer's machine already has local code execution.
- **`tokens.json` is `0600`**, written atomically.
- **No client secret exists**, so none can be extracted from the distributed jar.

## 16. Known limitations

- **A headless machine cannot complete this flow.** The URL is printed, but the redirect targets the loopback
  interface of whichever machine the browser is on. The device flow is the answer if that ever matters, and it
  drops in behind the same `TokenSupplier`.
- **A revoked refresh token surfaces as a fatal `401`**, one cycle after the revocation, with the instruction to
  run `handler login`. Distribution keeps working from the store the whole time, which is the behavior the core
  sync design already specifies for a rejected token.
- **The production FusionAuth must be 1.43.0 or later**, with `authorizedURLValidationPolicy: "AllowWildcards"`
  set on the Handler Application. Without it the wildcard redirect URL is rejected and no login can complete.
  This is a deployment prerequisite, not a code concern, but it is the one setting that has to be right in an
  environment this repository does not provision.

## References

- [RFC 8252 — OAuth 2.0 for Native Apps](https://datatracker.ietf.org/doc/html/rfc8252), §7.3 (loopback
  redirection) and §8.3 (why the IP literal is preferred over `localhost`)
- [AWS CLI adds PKCE-based authorization for SSO](https://aws.amazon.com/blogs/developer/aws-cli-adds-pkce-based-authorization-for-sso/)
  — the ephemeral-port implementation this design follows
- [cloudflare/workers-sdk #9208](https://github.com/cloudflare/workers-sdk/issues/9208) and
  [workers-oauth-provider #35](https://github.com/cloudflare/workers-oauth-provider/issues/35) — the fixed-port
  approach and the problems it causes
- [FusionAuth — OAuth URL validation](https://fusionauth.io/docs/lifecycle/authenticate-users/oauth/url-validation)
  — wildcard rules, including the port

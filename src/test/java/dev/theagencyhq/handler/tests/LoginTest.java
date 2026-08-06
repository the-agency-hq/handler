/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module java.net.http;
import module org.testng;

import dev.theagencyhq.handler.auth.AccessTokens;
import dev.theagencyhq.handler.auth.AuthConfiguration;
import dev.theagencyhq.handler.auth.AuthenticationException;
import dev.theagencyhq.handler.auth.Credentials;
import dev.theagencyhq.handler.auth.Introspection;
import dev.theagencyhq.handler.auth.Login;
import dev.theagencyhq.handler.auth.OAuthClient;
import dev.theagencyhq.handler.auth.OAuthTokenSupplier;
import dev.theagencyhq.handler.auth.PKCE;
import dev.theagencyhq.handler.auth.TokenStore;
import dev.theagencyhq.handler.auth.Tokens;

import static org.testng.Assert.*;

/**
 * Runs the real login flow against a local FusionAuth. The interactive browser step is driven over HTTP by
 * {@link FusionAuthBrowser} rather than opening a window, and the tokens land in a scratch directory rather than the
 * developer's real configuration.
 *
 * @author Brian Pontarelli
 */
public class LoginTest extends BaseTest {
  private static final String AGENT_EMAIL = "agent@theagencyhq.dev";
  private static final String AGENT_PASSWORD = "password";
  private static final String ISSUER = "http://localhost:9015";
  private static final String NOT_RUNNING = """
      FusionAuth is not running on http://localhost:9015. Start it with:

        cd src/test/fusionauth && docker compose up -d
      """;
  private static final String WRONG_INSTANCE = "The FusionAuth answering " + ISSUER + " is not the Handler's - it has"
      + " no Application [" + AuthConfiguration.CLIENT_ID + "]. Another project's FusionAuth is holding the port."
      + " Stop that one, then start this one with:\n\n  cd src/test/fusionauth && docker compose up -d\n";

  @Test
  public void aStoredRefreshTokenBuysANewAccessToken() {
    login().run(new PrintStream(OutputStream.nullOutputStream()));
    String original = store().load().accessToken();

    OAuthTokenSupplier supplier = new OAuthTokenSupplier(store(), new OAuthClient(new AuthConfiguration(ISSUER)));
    assertEquals(supplier.bearerToken(), original);
    assertTrue(supplier.refresh(), "The refresh grant should have been accepted");

    assertNotEquals(supplier.bearerToken(), original, "Refresh should have installed a new access token");
    assertEquals(store().load().accessToken(), supplier.bearerToken(), "The new token should have been persisted");
    assertEquals(email(supplier.bearerToken()), AGENT_EMAIL);
  }

  @BeforeClass
  public void beforeClass() {
    // Every FusionAuth answers /api/status, including an unrelated one that already holds 9015 - which has happened.
    // So the probe also asks the authorize endpoint about the Handler's client id: an instance without the
    // Application answers invalid_client, which is the difference between nothing running and the wrong thing running.
    // Both requests are read-only and unauthenticated.
    get(ISSUER + "/api/status");

    PKCE pkce = PKCE.generate();
    String authorizeURL = new AuthConfiguration(ISSUER).authorizeURL("probe", pkce.challenge(),
                                                                    "http://127.0.0.1:1/callback");
    if (get(authorizeURL).contains("invalid_client")) {
      throw new RuntimeException(WRONG_INSTANCE);
    }
  }

  @Test
  public void aLoggedInHandlerIntrospectsAsValidAndPassesTheDaemonPreflight() {
    login().run(new PrintStream(OutputStream.nullOutputStream()));
    String beforeVerify = store().load().accessToken();

    Introspection introspection = credentials().introspect();
    assertTrue(introspection.active(), "FusionAuth should report a freshly-minted token as active");
    assertEquals(introspection.email(), AGENT_EMAIL);
    assertEquals(introspection.audience(), AuthConfiguration.CLIENT_ID);
    assertEquals(introspection.issuer(), ISSUER);

    // The preflight spends the refresh token, so the daemon starts holding a token minted seconds ago
    credentials().verify();
    assertNotEquals(store().load().accessToken(), beforeVerify, "verify should have installed a fresh access token");
    assertTrue(credentials().introspect().active(), "The token verify installed should introspect as active too");
  }

  @Test
  public void anUnknownTokenIntrospectsAsInactive() {
    // Proves the introspection path reports the IdP's verdict rather than trusting anything local
    store().store(new Tokens("not-a-token-this-idp-ever-issued", "refresh"));

    AuthenticationException e = expectThrows(AuthenticationException.class, () -> credentials().introspect());
    assertTrue(e.getMessage().contains("not active"), "Message was: " + e.getMessage());
  }

  @Test
  public void loginStoresTokensAndTheAccessTokenCarriesTheEmail() {
    String email = login().run(new PrintStream(OutputStream.nullOutputStream()));

    assertEquals(email, AGENT_EMAIL);

    Tokens stored = store().load();
    assertTrue(stored.present(), "An access token should have been stored");
    assertFalse(stored.refreshToken().isEmpty(), "A refresh token should have been stored");
    assertEquals(email(stored.accessToken()), AGENT_EMAIL);
  }

  @Test
  public void twoLoginsInOneProcessBothSucceed() {
    // The ephemeral port is what makes this work; a fixed port would risk TIME_WAIT on the second bind
    assertEquals(login().run(new PrintStream(OutputStream.nullOutputStream())), AGENT_EMAIL);
    assertEquals(login().run(new PrintStream(OutputStream.nullOutputStream())), AGENT_EMAIL);

    assertTrue(store().load().present());
  }

  /**
   * @param url The URL to probe.
   * @return The response body. A connection failure or anything other than a 200 means FusionAuth is not answering at
   *     all, which is the "nothing is running" case rather than the "wrong instance" case.
   */
  private String get(String url) {
    try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      HttpResponse<String> response = client.send(
          HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(5)).build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IOException("It answered HTTP [" + response.statusCode() + "] for [" + url + "]");
      }

      return response.body();
    } catch (Exception e) {
      throw new RuntimeException(NOT_RUNNING, e);
    }
  }

  /**
   * Decodes a real FusionAuth access token through the production path, so these assertions also exercise the claim
   * validation in {@link AccessTokens} against tokens this repository did not mint.
   */
  private String email(String accessToken) {
    return new AccessTokens(new AuthConfiguration(ISSUER)).decode(accessToken).getString("email");
  }

  private Credentials credentials() {
    AuthConfiguration configuration = new AuthConfiguration(ISSUER);
    return new Credentials(store(), new OAuthClient(configuration), new AccessTokens(configuration));
  }

  private Login login() {
    AuthConfiguration configuration = new AuthConfiguration(ISSUER);
    return new Login(configuration, new OAuthClient(configuration), store(), new FusionAuthBrowser(AGENT_EMAIL, AGENT_PASSWORD));
  }

  private TokenStore store() {
    return new TokenStore(base.resolve("config/tokens.json"));
  }
}

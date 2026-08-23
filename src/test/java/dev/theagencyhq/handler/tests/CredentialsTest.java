/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class CredentialsTest extends BaseTest {
  private static final String CLIENT_ID = AuthConfiguration.CLIENT_ID;
  private FakeIdP idp;

  @Test
  public void introspectRejectsATokenTheIdPSaysIsInactive() {
    // A revoked token still decodes cleanly, so the IdP is the only thing that can catch this
    store().store(new Tokens("revoked", "refresh"));
    idp.script(200, "{\"active\":false}");

    AuthenticationException e = expectThrows(AuthenticationException.class, () -> credentials().introspect());
    assertTrue(e.getMessage().contains("not active"), "Message was: " + e.getMessage());
    assertTrue(e.getMessage().contains("handler login"), "Message was: " + e.getMessage());
  }

  @Test
  public void introspectRejectsATokenForAnotherApplication() {
    store().store(new Tokens("access", "refresh"));
    idp.script(200, active("11111111-2222-3333-4444-555555555555", issuer(), 3600));

    AuthenticationException e = expectThrows(AuthenticationException.class, () -> credentials().introspect());
    assertTrue(e.getMessage().contains("11111111-2222-3333-4444-555555555555"), "Message was: " + e.getMessage());
  }

  @Test
  public void introspectRejectsATokenFromAnotherIssuer() {
    store().store(new Tokens("access", "refresh"));
    idp.script(200, active(CLIENT_ID, "https://auth.example.com", 3600));

    AuthenticationException e = expectThrows(AuthenticationException.class, () -> credentials().introspect());
    assertTrue(e.getMessage().contains("https://auth.example.com"), "Message was: " + e.getMessage());
  }

  @Test
  public void introspectRejectsAnExpiredToken() {
    store().store(new Tokens("access", "refresh"));
    idp.script(200, active(CLIENT_ID, issuer(), -3600));

    AuthenticationException e = expectThrows(AuthenticationException.class, () -> credentials().introspect());
    assertTrue(e.getMessage().contains("expired"), "Message was: " + e.getMessage());
  }

  @Test
  public void introspectReportsTheClaimsWhenEverythingMatches() {
    store().store(new Tokens("access", "refresh"));
    idp.script(200, active(CLIENT_ID, issuer(), 3600));

    Introspection introspection = credentials().introspect();

    assertTrue(introspection.active());
    assertEquals(introspection.email(), "agent@theagencyhq.dev");
    assertEquals(introspection.audience(), CLIENT_ID);
    assertEquals(introspection.issuer(), issuer());
    assertEquals(idp.paths(), List.of("/oauth2/introspect"));
  }

  @Test
  public void introspectSaysSoWhenNothingIsStored() {
    AuthenticationException e = expectThrows(AuthenticationException.class, () -> credentials().introspect());
    assertTrue(e.getMessage().contains("handler login"), "Message was: " + e.getMessage());
    assertEquals(idp.paths(), List.of(), "There is nothing to introspect, so the IdP must not be called");
  }

  @Test
  public void verifyExchangesTheRefreshTokenAndPersistsWhatComesBack() {
    store().store(new Tokens("old-access", "old-refresh"));
    idp.script(200, "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\"}");

    credentials().verify();

    assertEquals(store().load().accessToken(), "new-access");
    assertEquals(store().load().refreshToken(), "new-refresh");
    assertEquals(idp.paths(), List.of("/oauth2/token"));
    assertTrue(idp.requestBodies().getFirst().contains("grant_type=refresh_token"),
               "Body was: " + idp.requestBodies().getFirst());
  }

  @Test
  public void verifyFailsWhenNothingIsStored() {
    AuthenticationException e = expectThrows(AuthenticationException.class, () -> credentials().verify());

    assertTrue(e.getMessage().contains("not logged in"), "Message was: " + e.getMessage());
    assertTrue(e.getMessage().contains("handler login"), "Message was: " + e.getMessage());
    assertEquals(idp.paths(), List.of(), "With nothing stored there is nothing to exchange");
  }

  @Test
  public void verifyFailsWhenOnlyAnAccessTokenIsStored() {
    // Nothing to renew with, so the daemon would run until the first 401 and then be stuck
    store().store(new Tokens("access-only", null));

    AuthenticationException e = expectThrows(AuthenticationException.class, () -> credentials().verify());
    assertTrue(e.getMessage().contains("handler login"), "Message was: " + e.getMessage());
    assertEquals(idp.paths(), List.of());
  }

  @Test
  public void verifyFailsWhenTheIdPRejectsTheRefreshToken() {
    store().store(new Tokens("access", "revoked-refresh"));
    idp.script(400, "{\"error\":\"invalid_grant\"}");

    AuthenticationException e = expectThrows(AuthenticationException.class, () -> credentials().verify());

    assertTrue(e.getMessage().contains("rejected"), "Message was: " + e.getMessage());
    assertTrue(e.getMessage().contains("handler login"), "Message was: " + e.getMessage());
    assertFalse(e instanceof IssuerUnreachableException, "A rejection is not an outage");
  }

  @Test
  public void verifyFailsWhenTheResponseCarriesNoAccessToken() {
    store().store(new Tokens("access", "refresh"));
    idp.script(200, "{\"token_type\":\"Bearer\"}");

    assertTrue(expectThrows(AuthenticationException.class, () -> credentials().verify())
                   .getMessage().contains("handler login"));
  }

  @Test
  public void verifySaysCheckTheNetworkRatherThanLogInWhenTheIssuerIsUnreachable() throws IOException {
    // Telling a developer to log in when their connection is down sends them to a browser that cannot load either
    store().store(new Tokens("access", "refresh"));
    int closed;
    try (ServerSocket socket = new ServerSocket(0)) {
      closed = socket.getLocalPort();
    }

    Credentials credentials = credentials("http://localhost:" + closed);
    IssuerUnreachableException e = expectThrows(IssuerUnreachableException.class, credentials::verify);

    assertTrue(e.getMessage().contains("could not reach"), "Message was: " + e.getMessage());
    assertTrue(e.getMessage().contains("network"), "Message was: " + e.getMessage());
    assertFalse(e.getMessage().contains("handler login"), "Wrong advice when the network is down: " + e.getMessage());
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

  /**
   * @param audience  The {@code aud} claim.
   * @param issuer    The {@code iss} claim.
   * @param expiresIn Seconds from now until {@code exp}, negative for an already-expired token.
   * @return An RFC 7662 introspection response body shaped like FusionAuth's.
   */
  private String active(String audience, String issuer, long expiresIn) {
    return """
        {"active":true,"aud":"%s","email":"agent@theagencyhq.dev","exp":%d,"iss":"%s",\
        "sub":"bbaff9de-9ce5-409f-97bf-fb2291eb176a"}"""
        .formatted(audience, Instant.now().plusSeconds(expiresIn).getEpochSecond(), issuer);
  }

  private Credentials credentials() {
    return credentials(issuer());
  }

  private Credentials credentials(String issuer) {
    AuthConfiguration configuration = new AuthConfiguration(issuer);
    return new Credentials(store(), new OAuthClient(configuration), new AccessTokens(configuration));
  }

  private String issuer() {
    return idp.url();
  }

  private TokenStore store() {
    return new TokenStore(base.resolve("config/tokens.json"));
  }
}

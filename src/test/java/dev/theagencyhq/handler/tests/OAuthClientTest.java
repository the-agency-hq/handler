/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class OAuthClientTest {
  private FakeIdP idp;

  @Test
  public void anErrorStatusFailsWithTheStatusAndTheOAuthErrorBody() {
    idp.script(400, "{\"error\":\"invalid_grant\"}");

    AuthenticationException e = expectThrows(AuthenticationException.class,
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
  public void refreshKeepsTheExistingRefreshTokenWhenTheResponseOmitsOne() {
    // FusionAuth may or may not rotate the refresh token; dropping it would force an unnecessary re-login
    idp.script(200, "{\"access_token\":\"new-at\"}");

    assertEquals(client().refresh("old-rt").refreshToken(), "old-rt");
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

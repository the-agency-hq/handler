/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import org.lattejava.jwt.Algorithm;
import org.lattejava.jwt.JWT;
import org.lattejava.jwt.JWTEncoder;
import org.lattejava.jwt.Signer;
import org.lattejava.jwt.Signers;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class AccessTokensTest {
  private static final String ISSUER = "http://localhost:9015";
  private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
  /**
   * The signature is never verified, so the secret is arbitrary — it exists only to produce a well-formed JWS.
   */
  private static final Signer SIGNER = Signers.forHMAC(Algorithm.HS256,
                                                       "a-test-secret-that-is-long-enough-for-hs256-signing");

  @Test
  public void aMissingAudienceIsRejected() {
    AuthenticationException e = expectThrows(AuthenticationException.class,
                                             () -> decode(token().audience((List<String>) null)));
    assertTrue(e.getMessage().contains(AuthConfiguration.CLIENT_ID), "Message was: " + e.getMessage());
  }

  @Test
  public void aTokenForAnotherApplicationIsRejected() {
    // Genuine, correctly signed, right tenant — but minted for a different Application
    AuthenticationException e = expectThrows(AuthenticationException.class,
                                             () -> decode(token().audience("11111111-2222-3333-4444-555555555555")));

    assertTrue(e.getMessage().contains("11111111-2222-3333-4444-555555555555"), "Message was: " + e.getMessage());
    assertTrue(e.getMessage().contains(AuthConfiguration.CLIENT_ID), "Message was: " + e.getMessage());
  }

  @Test
  public void aTokenFromAnotherIssuerIsRejected() {
    AuthenticationException e = expectThrows(AuthenticationException.class,
                                             () -> decode(token().issuer("https://auth.example.com")));

    assertTrue(e.getMessage().contains("[https://auth.example.com]"), "Message was: " + e.getMessage());
    assertTrue(e.getMessage().contains("[" + ISSUER + "]"), "Message was: " + e.getMessage());
  }

  @Test
  public void aValidTokenYieldsItsClaims() {
    JWT jwt = decode(token());

    assertEquals(jwt.getString("email"), "agent@theagencyhq.dev");
    assertEquals(jwt.issuer(), ISSUER);
    assertEquals(jwt.audience(), List.of(AuthConfiguration.CLIENT_ID));
  }

  @Test
  public void anExpiredTokenIsRejectedOnceItIsOutsideTheSkew() {
    // 61 seconds past expiry: one second beyond the 60-second tolerance
    AuthenticationException e = expectThrows(AuthenticationException.class,
                                             () -> decode(token().expiresAt(NOW.minusSeconds(61))));
    assertTrue(e.getMessage().contains("expired"), "Message was: " + e.getMessage());
  }

  @Test
  public void aTokenExpiredWithinTheSkewIsStillAccepted() {
    // A laptop clock a few seconds fast must not reject a token the IdP still considers valid
    assertEquals(decode(token().expiresAt(NOW.minusSeconds(59))).getString("email"), "agent@theagencyhq.dev");
  }

  @Test
  public void aTokenNotYetValidIsRejectedOnceItIsOutsideTheSkew() {
    AuthenticationException e = expectThrows(AuthenticationException.class,
                                             () -> decode(token().notBefore(NOW.plusSeconds(61))));
    assertTrue(e.getMessage().contains("not valid until"), "Message was: " + e.getMessage());
  }

  @Test
  public void aTokenNotYetValidWithinTheSkewIsAccepted() {
    assertEquals(decode(token().notBefore(NOW.plusSeconds(59))).getString("email"), "agent@theagencyhq.dev");
  }

  @Test
  public void anAbsentEmailClaimYieldsNull() {
    // The Handler prints "Login successful." rather than an email in this case, so null must not become an exception
    assertNull(decode(JWT.builder()
                         .issuer(ISSUER)
                         .audience(AuthConfiguration.CLIENT_ID)
                         .expiresAt(NOW.plusSeconds(3600))).getString("email"));
  }

  @Test(dataProvider = "malformed")
  public void malformedTokensAreRejectedWithoutEchoingTheToken(String encodedJWT) {
    AuthenticationException e = expectThrows(AuthenticationException.class,
                                             () -> new AccessTokens(new AuthConfiguration(ISSUER), clock())
                                                 .decode(encodedJWT));

    assertTrue(!e.getMessage().contains(encodedJWT), "A credential must never be echoed: " + e.getMessage());
  }

  @DataProvider
  public Object[][] malformed() {
    return new Object[][]{
        {"not-a-jwt"},
        {"onlyoneheader."},
        {"aGVhZGVy.!!!not-base64!!!.signature"},
        {"aGVhZGVy..signature"}
    };
  }

  @Test
  public void unknownClaimsAreIgnored() {
    // A real FusionAuth access token carries a dozen claims the Handler does not model
    JWT jwt = decode(token().claim("roles", List.of("admin")).claim("scope", "openid offline_access"));

    assertEquals(jwt.getString("email"), "agent@theagencyhq.dev");
    assertEquals(jwt.getList("roles"), List.of("admin"));
  }

  private Clock clock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private JWT decode(JWT.Builder builder) {
    String encoded = new JWTEncoder().encode(builder.build(), SIGNER);
    return new AccessTokens(new AuthConfiguration(ISSUER), clock()).decode(encoded);
  }

  /**
   * @return A builder for a token this Handler should accept, which each test then spoils in exactly one way.
   */
  private JWT.Builder token() {
    return JWT.builder()
              .issuer(ISSUER)
              .audience(AuthConfiguration.CLIENT_ID)
              .subject("f1e33ab3-027f-47c5-bb07-8dd8ab37a2d3")
              .issuedAt(NOW.minusSeconds(10))
              .expiresAt(NOW.plusSeconds(3600))
              .claim("email", "agent@theagencyhq.dev");
  }
}

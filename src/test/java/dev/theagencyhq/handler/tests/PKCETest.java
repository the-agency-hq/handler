/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

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

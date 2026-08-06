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

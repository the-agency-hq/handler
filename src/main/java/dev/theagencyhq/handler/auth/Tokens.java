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

  /**
   * Reports only whether each token is there. The record's generated {@code toString()} would render both credentials
   * in full, which turns any future log line or string concatenation that happens to include a {@code Tokens} into a
   * credential leak. Redacting here makes "tokens are never logged" a property of the type rather than a convention
   * every caller has to remember.
   *
   * @return The redacted description.
   */
  @Override
  public String toString() {
    return "Tokens[accessToken=" + (accessToken.isEmpty() ? "absent" : "present") + ", refreshToken="
        + (refreshToken.isEmpty() ? "absent" : "present") + "]";
  }
}

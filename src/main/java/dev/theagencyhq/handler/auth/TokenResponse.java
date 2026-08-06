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

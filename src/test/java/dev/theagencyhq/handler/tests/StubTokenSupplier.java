/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module dev.theagencyhq.handler;

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

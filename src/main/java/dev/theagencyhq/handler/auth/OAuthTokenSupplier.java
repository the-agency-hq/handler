/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

import dev.theagencyhq.handler.agency.TokenSupplier;

/**
 * Serves the stored access token and renews it with the refresh grant when The Agency rejects one.
 *
 * <p>The token is read from disk once and cached, so the per-request path does no file I/O. {@link #refresh()} is the
 * only thing that goes back to disk, and it starts by checking whether another process — a {@code handler login} the
 * developer just ran — already replaced the token. That check is what lets a login take effect in a running daemon
 * without a restart.
 *
 * @author Brian Pontarelli
 */
public class OAuthTokenSupplier implements TokenSupplier {
  private static final System.Logger LOG = System.getLogger(OAuthTokenSupplier.class.getName());

  private final OAuthClient client;
  private final TokenStore store;
  private Tokens tokens;

  public OAuthTokenSupplier(TokenStore store, OAuthClient client) {
    this.store = store;
    this.client = client;
  }

  @Override
  public synchronized String bearerToken() {
    return cached().accessToken();
  }

  @Override
  public synchronized boolean refresh() {
    // Everything here is inside the try, including the load: an unreadable or hand-mangled tokens.json is a reason to
    // report failure, which the caller turns into a 401 naming the real fix, not an exception out of the receive cycle
    try {
      Tokens onDisk = store.load();
      if (tokens == null) {
        tokens = onDisk;
      }

      // Another process may have logged in since this token was cached. Adopting costs nothing and skips the IdP.
      if (onDisk.present() && !onDisk.accessToken().equals(tokens.accessToken())) {
        LOG.log(System.Logger.Level.DEBUG, "Adopted an access token written by another process");
        tokens = onDisk;
        return true;
      }

      tokens = onDisk;
      if (tokens.refreshToken().isEmpty()) {
        LOG.log(System.Logger.Level.WARNING, "There is no refresh token stored. Run [handler login].");
        return false;
      }

      // Cached before it is stored. The IdP may have rotated the refresh token, which kills the old one server-side,
      // so a failed write must not leave the process holding a pair that no longer works.
      Tokens refreshed = client.refresh(tokens.refreshToken());
      tokens = refreshed;
      store.store(refreshed);
      return true;
    } catch (RuntimeException e) {
      // Never fatal here: the caller turns this into a 401 that tells the developer to log in again
      LOG.log(System.Logger.Level.WARNING, "Unable to refresh the access token. Message was [{0}]", e.getMessage());
      return false;
    }
  }

  private Tokens cached() {
    if (tokens == null) {
      try {
        tokens = store.load();
      } catch (RuntimeException e) {
        // Caching EMPTY sends an empty bearer token, so The Agency answers 401 and refresh() reports the real cause
        LOG.log(System.Logger.Level.WARNING, "Unable to read the stored tokens. Message was [{0}]", e.getMessage());
        tokens = Tokens.EMPTY;
      }
    }

    return tokens;
  }
}

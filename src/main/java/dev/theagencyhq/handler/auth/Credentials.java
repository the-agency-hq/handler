/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * The two checks the CLI runs against the stored credentials: one before the daemon starts, and one when a developer
 * asks {@code handler status} what is wrong.
 *
 * <p>Both fail by throwing {@link AuthenticationException} carrying a message written for a terminal, because in both
 * cases the message is the entire output a developer gets.
 *
 * @author Brian Pontarelli
 */
public class Credentials {
  private final AccessTokens accessTokens;
  private final OAuthClient client;
  private final TokenStore store;

  public Credentials(TokenStore store, OAuthClient client, AccessTokens accessTokens) {
    this.store = store;
    this.client = client;
    this.accessTokens = accessTokens;
  }

  /**
   * Asks the IdP what it makes of the stored access token, and checks the claims it answers with.
   *
   * @return The introspection response, already validated.
   * @throws AuthenticationException When nothing is stored, the IdP rejects the token, or a claim does not match.
   */
  public Introspection introspect() {
    Tokens tokens = store.load();
    if (!tokens.present()) {
      throw new AuthenticationException("No access token is stored. Run [handler login].");
    }

    Introspection introspection = client.introspect(tokens.accessToken());
    accessTokens.validate(introspection);
    return introspection;
  }

  /**
   * Verifies, before the daemon commits to running, that there is a credential and that the IdP still honors it.
   *
   * <p>The check is a real refresh rather than a local decode. A stored access token can look perfectly valid and
   * still be dead — the refresh token revoked, the user removed, the Application reconfigured — and the daemon would
   * discover that only on its first receive cycle, having already reported a successful start. Spending the refresh
   * here also means the daemon begins its life holding a token minted seconds ago rather than one of unknown age.
   *
   * @throws AuthenticationException When no usable credential is stored or the IdP will not renew it. The message
   *     distinguishes a rejected credential from an issuer that could not be reached, because the two call for
   *     different actions.
   */
  public void verify() {
    Tokens tokens = store.load();
    if (!tokens.present() || tokens.refreshToken().isEmpty()) {
      throw new AuthenticationException("""
          The Handler is not logged in, so it cannot receive Briefs from The Agency.

          Run [handler login] and start it again.""");
    }

    Tokens refreshed;
    try {
      refreshed = client.refresh(tokens.refreshToken());
    } catch (IssuerUnreachableException e) {
      throw new IssuerUnreachableException("""
          The Handler could not reach The Agency's identity provider to check its credentials, so it did not start.

          %s

          Check your network and start it again.""".formatted(e.getMessage()), e);
    } catch (AuthenticationException e) {
      throw new AuthenticationException("""
          The Handler's stored credentials were rejected, so it did not start.

          %s

          Run [handler login] and start it again.""".formatted(e.getMessage()), e);
    }

    if (!refreshed.present()) {
      throw new AuthenticationException("""
          The identity provider renewed the Handler's credentials without returning an access token, so it did not \
          start.

          Run [handler login] and start it again.""");
    }

    // Persist before the daemon reads: OAuthTokenSupplier loads lazily on its first request, so it picks this up
    store.store(refreshed);
  }
}

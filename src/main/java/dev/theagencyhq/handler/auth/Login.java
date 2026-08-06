/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

import org.lattejava.jwt.JWT;

/**
 * Runs the OAuth 2.0 Authorization Code flow with PKCE and stores the resulting tokens.
 *
 * @author Brian Pontarelli
 */
public class Login {
  private static final Duration BROWSER_TIMEOUT = Duration.ofMinutes(2);
  private final AccessTokens accessTokens;
  private final Browser browser;
  private final OAuthClient client;
  private final AuthConfiguration configuration;
  private final TokenStore store;

  public Login(AuthConfiguration configuration, OAuthClient client, TokenStore store, Browser browser) {
    this.configuration = configuration;
    this.client = client;
    this.store = store;
    this.browser = browser;
    this.accessTokens = new AccessTokens(configuration);
  }

  private static String randomState() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Runs the flow to completion, writing the tokens on success.
   *
   * @param out Where the browser instructions are printed.
   * @return The email on the access token, or null when it carries none.
   */
  public String run(PrintStream out) {
    PKCE pkce = PKCE.generate();
    String state = randomState();

    LoopbackServer server = new LoopbackServer(state);
    server.start();

    // The OS picks the port at bind time, so the redirect URI is only knowable after start(). The same URI has to go
    // out on both the authorize request and the token request, so capture it once here.
    String redirectURI = server.redirectURI();

    String code;
    try {
      browser.open(configuration.authorizeURL(state, pkce.challenge(), redirectURI), out);
      code = server.awaitCode(BROWSER_TIMEOUT);
    } finally {
      server.stop();
    }

    Tokens tokens = client.exchangeCode(code, pkce.verifier(), redirectURI);

    // Validate before persisting. A token that is not for this Handler must never reach tokens.json, where the daemon
    // would pick it up and present it to The Agency on every cycle.
    JWT jwt = accessTokens.decode(tokens.accessToken());
    store.store(tokens);

    return jwt.getString("email");
  }
}

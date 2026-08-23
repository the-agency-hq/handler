/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

import org.lattejava.jwt.JWT;

/**
 * The {@code login} subcommand: runs the OAuth 2.0 Authorization Code flow with PKCE, stores the resulting tokens,
 * and reports the outcome.
 *
 * @author Brian Pontarelli
 */
public class Login {
  private static final Duration BROWSER_TIMEOUT = Duration.ofMinutes(2);

  private final AccessTokens accessTokens;
  private final Browser browser;
  private final OAuthClient client;
  private final AuthConfiguration configuration;
  private final PrintStream out;
  private final TokenStore store;

  public Login(AuthConfiguration configuration, OAuthClient client, TokenStore store, Browser browser,
               PrintStream out) {
    this.configuration = configuration;
    this.client = client;
    this.store = store;
    this.browser = browser;
    this.out = out;
    this.accessTokens = new AccessTokens(configuration);
  }

  private static String randomState() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public int run() {
    try {
      String email = authenticate();
      out.println(email == null ? "Login successful." : "Logged in as [" + email + "]");
      return 0;
    } catch (AuthenticationException e) {
      out.println(e.getMessage());
      return 1;
    }
  }

  /**
   * Runs the flow to completion, writing the tokens on success. Protected rather than private so the tests can drive
   * the flow directly and read the email it returns instead of parsing the printed report.
   *
   * @return The email on the access token, or null when it carries none.
   */
  protected String authenticate() {
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

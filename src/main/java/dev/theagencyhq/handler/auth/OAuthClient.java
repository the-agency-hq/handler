/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;
import module java.net.http;

/**
 * Calls the IdP token endpoint as a public client — the PKCE code verifier stands in for a client secret, because a
 * jar on a developer's machine cannot hold one.
 *
 * @author Brian Pontarelli
 */
public class OAuthClient {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final AuthConfiguration configuration;

  public OAuthClient(AuthConfiguration configuration) {
    this.configuration = configuration;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * Exchanges an authorization code for tokens.
   *
   * @param code         The authorization code captured on the loopback redirect.
   * @param codeVerifier The PKCE code verifier matching the challenge sent on the authorize request.
   * @param redirectURI  The same redirect URI sent on the authorize request. The IdP requires the two to match
   *                     exactly, so it carries the ephemeral port the loopback server bound.
   * @return The tokens.
   */
  public Tokens exchangeCode(String code, String codeVerifier, String redirectURI) {
    String form = "grant_type=authorization_code" +
        "&code=" + encode(code) +
        "&redirect_uri=" + encode(redirectURI) +
        "&client_id=" + encode(AuthConfiguration.CLIENT_ID) +
        "&code_verifier=" + encode(codeVerifier);

    return post(form, null);
  }

  /**
   * Renews the access token with the refresh grant. This sends no redirect URI, which is what keeps renewal
   * independent of the loopback server and its ephemeral port.
   *
   * @param refreshToken The stored refresh token.
   * @return The new tokens, carrying the supplied refresh token when the IdP did not rotate it.
   */
  public Tokens refresh(String refreshToken) {
    String form = "grant_type=refresh_token" +
        "&refresh_token=" + encode(refreshToken) +
        "&client_id=" + encode(AuthConfiguration.CLIENT_ID);

    return post(form, refreshToken);
  }

  /**
   * Asks the IdP whether an access token is still live, and what it says. Unlike decoding the token locally, this
   * reflects revocation — a token the IdP has since invalidated still decodes cleanly but introspects as inactive.
   *
   * @param accessToken The token to introspect.
   * @return The introspection response, which reports {@code active: false} rather than failing for a token the IdP
   *     does not recognize.
   */
  public Introspection introspect(String accessToken) {
    String form = "token=" + encode(accessToken) + "&client_id=" + encode(AuthConfiguration.CLIENT_ID);

    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(configuration.introspectEndpoint())
                                     .header("Content-Type", "application/x-www-form-urlencoded")
                                     .header("Accept", "application/json")
                                     .timeout(REQUEST_TIMEOUT)
                                     .POST(HttpRequest.BodyPublishers.ofString(form))
                                     .build();

    HttpResponse<String> response = send(request, configuration.introspectEndpoint());
    if (response.statusCode() != 200) {
      throw new AuthenticationException("The introspection request failed with status [" + response.statusCode()
          + "] and body [" + response.body() + "]");
    }

    try {
      return Introspection.fromJSON(response.body().getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new AuthenticationException("The introspection response was not valid JSON. Message was ["
          + e.getMessage() + "]", e);
    }
  }

  /**
   * @param form                 The URL-encoded request body.
   * @param existingRefreshToken Returned in place of an absent {@code refresh_token}, or null when there is none.
   * @return The tokens.
   */
  private Tokens post(String form, String existingRefreshToken) {
    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(configuration.tokenEndpoint())
                                     .header("Content-Type", "application/x-www-form-urlencoded")
                                     .header("Accept", "application/json")
                                     .timeout(REQUEST_TIMEOUT)
                                     .POST(HttpRequest.BodyPublishers.ofString(form))
                                     .build();

    HttpResponse<String> response = send(request, configuration.tokenEndpoint());

    // The body here is an OAuth error object, never a credential, so it is safe to surface
    if (response.statusCode() != 200) {
      throw new AuthenticationException("The token request failed with status [" + response.statusCode()
          + "] and body [" + response.body() + "]");
    }

    TokenResponse parsed;
    try {
      parsed = TokenResponse.fromJSON(response.body().getBytes(StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new AuthenticationException("The token response was not valid JSON. Message was [" + e.getMessage() + "]", e);
    }

    String at = parsed.accessToken();
    if (at == null || at.isBlank()) {
      throw new AuthenticationException("The token response did not contain an access token. Error was ["
          + parsed.error() + "] and description was [" + parsed.errorDescription() + "]");
    }

    String rt = parsed.refreshToken();
    String refreshToken = rt == null || rt.isBlank() ? existingRefreshToken : rt;
    return new Tokens(at, refreshToken);
  }

  /**
   * Sends one request, converting a transport failure into {@link IssuerUnreachableException} so callers can tell
   * "the IdP said no" from "the IdP was not there".
   *
   * @param request  The request.
   * @param endpoint Named in the failure message.
   * @return The response.
   */
  private HttpResponse<String> send(HttpRequest request, URI endpoint) {
    try (HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()) {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IssuerUnreachableException("Could not reach [" + endpoint + "]. Reason was [" + reason(e) + "]", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IssuerUnreachableException("The request to [" + endpoint + "] was interrupted.", e);
    }
  }

  /**
   * Finds something worth printing in a transport failure. {@code HttpClient} routinely raises a
   * {@code ConnectException} whose own message is null and whose cause carries the detail, so the bare message would
   * read "Reason was [null]" for the single most common failure there is — nothing listening on the port.
   *
   * @param failure The transport failure.
   * @return The first non-blank message in the cause chain, or the simple class name when there is none.
   */
  private String reason(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t.getMessage() != null && !t.getMessage().isBlank()) {
        return t.getMessage();
      }
    }

    return failure.getClass().getSimpleName();
  }
}

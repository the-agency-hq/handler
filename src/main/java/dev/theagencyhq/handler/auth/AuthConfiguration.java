/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Holds the resolved OAuth issuer and the hardcoded public-client settings, and builds the OAuth endpoint URLs the
 * login flow uses.
 *
 * <p>The Handler is a public client — it ships as a jar on developers' machines, so it cannot hold a secret. PKCE is
 * what makes that safe, and the FusionAuth Application requires it.
 *
 * @author Brian Pontarelli
 */
public record AuthConfiguration(String issuer) {
  public static final String CLIENT_ID = "fa83bc7c-f1c5-48af-8ecb-6c09cf766d73";
  public static final String DEFAULT_ISSUER = "https://auth.theagencyhq.dev";
  public static final String SCOPES = "openid offline_access";

  public AuthConfiguration {
    String resolved = issuer;
    if (resolved != null && !resolved.isBlank()) {
      resolved = resolved.trim();
      while (resolved.endsWith("/")) {
        resolved = resolved.substring(0, resolved.length() - 1);
      }

      if (!valid(resolved)) {
        throw new AuthenticationException("The configured authURL [" + issuer + "] is not an absolute http or https URL. " +
            "Fix authURL in handler.json and restart the service.");
      }
      issuer = resolved;
    } else {
      issuer = DEFAULT_ISSUER;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean valid(String issuer) {
    URI uri;
    try {
      uri = new URI(issuer);
    } catch (URISyntaxException e) {
      return false;
    }

    String scheme = uri.getScheme();
    return uri.isAbsolute() && uri.getHost() != null && (scheme.equals("http") || scheme.equals("https"));
  }

  /**
   * Builds the OAuth authorization request URL for the IdP login page.
   *
   * @param state         A random nonce echoed back on the redirect to defend against CSRF.
   * @param codeChallenge The base64url-encoded SHA-256 of the PKCE code verifier.
   * @param redirectURI   The loopback redirect URI, which carries the ephemeral port the loopback server bound.
   * @return The fully-formed authorize URL.
   */
  public String authorizeURL(String state, String codeChallenge, String redirectURI) {
    return issuer + "/oauth2/authorize?response_type=code" +
        "&client_id=" + encode(CLIENT_ID) +
        "&redirect_uri=" + encode(redirectURI) +
        "&scope=" + encode(SCOPES) +
        "&code_challenge=" + encode(codeChallenge) +
        "&code_challenge_method=S256" +
        "&state=" + encode(state);
  }

  /**
   * The RFC 7662 token introspection endpoint. FusionAuth does not advertise it in its OpenID configuration, but it
   * accepts a public client — {@code client_id} with no secret — which is what lets the Handler use it.
   *
   * @return The introspection endpoint.
   */
  public URI introspectEndpoint() {
    return URI.create(issuer + "/oauth2/introspect");
  }

  public URI tokenEndpoint() {
    return URI.create(issuer + "/oauth2/token");
  }
}

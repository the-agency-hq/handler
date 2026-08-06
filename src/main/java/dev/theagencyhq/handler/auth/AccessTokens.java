/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

import org.lattejava.jwt.JWT;
import org.lattejava.jwt.JWTDecoder;
import org.lattejava.jwt.JWTException;

/**
 * Decodes an access token and validates the claims the Handler depends on.
 *
 * <p>The signature is deliberately not verified. The token arrives directly from the IdP over TLS in the response to a
 * request the Handler itself made, so there is no untrusted party in between to defend against. Verifying would also
 * put the IdP's JWKS endpoint on the path of every login and every refresh, which turns a transient IdP outage into a
 * failed login for no gain in this position. The Agency verifies the signature, because The Agency receives the token
 * from somewhere it does not control.
 *
 * <p>The claims are another matter, and cost neither a network call nor a key: {@code iss} and {@code aud} catch a
 * token that is genuine but was minted for a different tenant or a different Application, and {@code exp} and
 * {@code nbf} catch a stale one read back from {@code tokens.json}, which — unlike a freshly exchanged token — did not
 * come straight from the IdP.
 *
 * @author Brian Pontarelli
 */
public class AccessTokens {
  /**
   * Tolerance applied to {@code exp} and {@code nbf}. Without it, a correctly-issued token is rejected on a machine
   * whose clock runs a few seconds ahead of the IdP's.
   */
  static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
  private static final JWTDecoder DECODER = new JWTDecoder();

  private final AuthConfiguration configuration;
  private final Clock clock;

  public AccessTokens(AuthConfiguration configuration) {
    this(configuration, Clock.systemUTC());
  }

  /**
   * Builds an instance against an arbitrary time source. This is the injection seam that makes the {@code exp} and
   * {@code nbf} checks testable — the tests live in their own module, so a package-private seam would not reach them,
   * and placing a token on either side of the skew boundary must not require sleeping.
   *
   * @param configuration Supplies the issuer every token is checked against.
   * @param clock         The time source for the {@code exp} and {@code nbf} checks.
   */
  public AccessTokens(AuthConfiguration configuration, Clock clock) {
    this.configuration = configuration;
    this.clock = clock;
  }

  /**
   * Decodes an access token and validates its claims.
   *
   * @param encodedJWT The compact JWS returned by the token endpoint.
   * @return The decoded token.
   * @throws AuthenticationException When the token is malformed or any validated claim does not match. The message
   *     never carries the token, because it is a credential.
   */
  public JWT decode(String encodedJWT) {
    JWT jwt;
    try {
      // decodeUnsecured skips the decoder's own exp and nbf checks along with the signature, so both are validated
      // explicitly below rather than inherited
      jwt = DECODER.decodeUnsecured(encodedJWT);
    } catch (JWTException e) {
      throw new AuthenticationException("The access token could not be decoded. Message was [" + e.getMessage() + "]", e);
    }

    if (!configuration.issuer().equals(jwt.issuer())) {
      throw new AuthenticationException("The access token was issued by [" + jwt.issuer() + "] but this Handler is"
          + " configured for [" + configuration.issuer() + "]. Check authURL in handler.json, then run [handler login].");
    }

    if (jwt.audience() == null || !jwt.audience().contains(AuthConfiguration.CLIENT_ID)) {
      throw new AuthenticationException("The access token was issued for [" + jwt.audience() + "] rather than for this"
          + " Handler [" + AuthConfiguration.CLIENT_ID + "]. Run [handler login].");
    }

    Instant now = clock.instant();
    if (jwt.isExpired(now.minus(CLOCK_SKEW))) {
      throw new AuthenticationException("The access token expired at [" + jwt.expiresAt() + "]. Run [handler login].");
    }

    if (jwt.isUnavailableForProcessing(now.plus(CLOCK_SKEW))) {
      throw new AuthenticationException("The access token is not valid until [" + jwt.notBefore() + "], which is in the"
          + " future. Check this machine's clock.");
    }

    return jwt;
  }

  /**
   * Applies the same claim rules {@link #decode} applies to a JWT, to what the IdP said about a token rather than to
   * what the token says about itself. The two can disagree: a revoked token still carries valid-looking claims, and
   * only the IdP knows it is dead.
   *
   * @param introspection The introspection response.
   * @throws AuthenticationException When the token is inactive or any claim does not match.
   */
  public void validate(Introspection introspection) {
    if (!introspection.active()) {
      throw new AuthenticationException("The identity provider reports the access token is not active. It has expired"
          + " or been revoked. Run [handler login].");
    }

    if (!configuration.issuer().equals(introspection.issuer())) {
      throw new AuthenticationException("The access token was issued by [" + introspection.issuer() + "] but this"
          + " Handler is configured for [" + configuration.issuer() + "]. Check authURL in handler.json.");
    }

    if (!AuthConfiguration.CLIENT_ID.equals(introspection.audience())) {
      throw new AuthenticationException("The access token was issued for [" + introspection.audience() + "] rather"
          + " than for this Handler [" + AuthConfiguration.CLIENT_ID + "]. Run [handler login].");
    }

    Instant expiresAt = introspection.expiresAtInstant();
    if (expiresAt == null) {
      throw new AuthenticationException("The introspection response carried no expiration, so the token cannot be"
          + " trusted to still be valid. Run [handler login].");
    }

    if (!expiresAt.isAfter(clock.instant().minus(CLOCK_SKEW))) {
      throw new AuthenticationException("The access token expired at [" + expiresAt + "]. Run [handler login].");
    }
  }
}

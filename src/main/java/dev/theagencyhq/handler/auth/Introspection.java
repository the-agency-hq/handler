/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.auth.internal.IntrospectionJSON;

/**
 * The RFC 7662 introspection response. Only the fields the Handler checks are modeled; a real response also carries
 * {@code iat}, {@code jti}, {@code tid}, {@code roles}, and more, all of which the processor ignores.
 *
 * <p>{@code aud} is a single string here rather than a list, because FusionAuth returns the Application id. RFC 7662
 * permits an array, so a different IdP could break this — worth knowing if the Handler ever points at one.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Introspection(boolean active, @JSONField(name = "aud") String audience, String email,
                            @JSONField(name = "exp") Long expiresAt, @JSONField(name = "iss") String issuer,
                            @JSONField(name = "sub") String subject) {
  public static Introspection fromJSON(byte[] json) {
    return IntrospectionJSON.fromJSON(json);
  }

  /**
   * @return {@link #expiresAt()} as an {@link Instant}, or null when the response carried no {@code exp}.
   */
  public Instant expiresAtInstant() {
    return expiresAt == null ? null : Instant.ofEpochSecond(expiresAt);
  }
}

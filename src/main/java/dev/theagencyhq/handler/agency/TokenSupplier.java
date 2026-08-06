/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

/**
 * Supplies the bearer token for Agency requests, and renews it when The Agency rejects one. Every mechanic of how a
 * token is obtained, stored, and renewed lives behind this interface — {@link AgencyClient} only ever learns whether
 * a better token is now available.
 *
 * @author Brian Pontarelli
 */
public interface TokenSupplier {
  String bearerToken();

  /**
   * Attempts to obtain a usable access token after the current one was rejected. Implementations must not throw: an
   * IdP that is unreachable is a reason to report failure, not a reason to end the receive cycle.
   *
   * @return Whether an access token different from the rejected one is now available.
   */
  boolean refresh();
}

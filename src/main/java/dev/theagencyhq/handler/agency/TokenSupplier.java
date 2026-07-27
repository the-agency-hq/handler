/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

/**
 * Supplies the bearer token for Agency requests. The OAuth device flow lands behind this interface with no change to
 * {@link AgencyClient}.
 *
 * @author Brian Pontarelli
 */
public interface TokenSupplier {
  String bearerToken();
}

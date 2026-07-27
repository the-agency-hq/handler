/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;

import dev.theagencyhq.handler.config.HandlerConfig;

/**
 * Reads the static bearer token out of {@code handler.json}.
 *
 * @author Brian Pontarelli
 */
public class ConfigTokenSupplier implements TokenSupplier {
  private final HandlerConfig config;

  public ConfigTokenSupplier(HandlerConfig config) {
    this.config = config;
  }

  @Override
  public String bearerToken() {
    return config.accessToken();
  }
}

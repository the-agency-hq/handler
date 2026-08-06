/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Opens a URL for the developer to complete the interactive part of the login. Production launches the system web
 * browser; tests substitute an implementation that drives the login over HTTP.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface Browser {
  void open(String url, PrintStream out);
}

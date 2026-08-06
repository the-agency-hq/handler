/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

/**
 * Thrown when any step of the login flow fails. The message is written for a developer reading a terminal, because
 * that is the only place it is ever shown.
 *
 * @author Brian Pontarelli
 */
public class AuthenticationException extends RuntimeException {
  public AuthenticationException(String message) {
    super(message);
  }

  public AuthenticationException(String message, Throwable cause) {
    super(message, cause);
  }
}

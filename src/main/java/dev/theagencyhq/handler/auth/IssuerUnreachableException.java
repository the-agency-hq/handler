/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

/**
 * Thrown when the IdP could not be reached at all, as opposed to reaching it and being turned away.
 *
 * <p>The distinction is the difference between two pieces of advice. A rejected credential means log in again; an
 * unreachable issuer means check the network, and telling a developer to log in while their connection is down sends
 * them to a browser that cannot load the page either.
 *
 * @author Brian Pontarelli
 */
public class IssuerUnreachableException extends AuthenticationException {
  public IssuerUnreachableException(String message, Throwable cause) {
    super(message, cause);
  }
}

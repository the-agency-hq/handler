/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module org.testng;

import dev.theagencyhq.handler.auth.Tokens;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class TokensTest {
  @Test
  public void toStringReportsPresenceWithoutRenderingEitherToken() {
    // The record's generated toString would print both credentials, so any log line holding a Tokens would leak them
    String rendered = new Tokens("super-secret-access", "super-secret-refresh").toString();

    assertFalse(rendered.contains("super-secret-access"), "The access token must never be rendered: " + rendered);
    assertFalse(rendered.contains("super-secret-refresh"), "The refresh token must never be rendered: " + rendered);
    assertEquals(rendered, "Tokens[accessToken=present, refreshToken=present]");
  }

  @Test
  public void toStringSaysWhichTokensAreAbsent() {
    assertEquals(Tokens.EMPTY.toString(), "Tokens[accessToken=absent, refreshToken=absent]");
    assertEquals(new Tokens("access", null).toString(), "Tokens[accessToken=present, refreshToken=absent]");
  }

  @Test
  public void toStringSurvivesConcatenationIntoAMessage() {
    // The leak this guards against is a string concatenation, not a deliberate call to toString
    String message = "Refreshing " + new Tokens("super-secret-access", "super-secret-refresh");

    assertFalse(message.contains("super-secret"), "Message was: " + message);
    assertTrue(message.contains("accessToken=present"), "Message was: " + message);
  }
}

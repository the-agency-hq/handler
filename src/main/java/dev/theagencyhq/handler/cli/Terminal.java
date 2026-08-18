/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;

/**
 * Switches the controlling terminal in and out of raw mode, behind an interface so interactive commands can be tested
 * with plain streams.
 *
 * @author Brian Pontarelli
 */
public interface Terminal {
  /**
   * Puts the terminal into raw mode: no line buffering and no echo, so single key presses arrive immediately.
   *
   * @throws IOException When stdin is not an interactive terminal or its mode could not be changed.
   */
  void enterRawMode() throws IOException;

  /**
   * Restores the terminal to the mode it was in before {@link #enterRawMode()}. Never throws — restoration runs on
   * every exit path, including failures, where a second error would only bury the first.
   */
  void restore();
}

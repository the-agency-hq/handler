/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module dev.theagencyhq.handler;

/**
 * A {@link Terminal} that only records the mode changes, so interactive commands can run against plain streams.
 *
 * @author Brian Pontarelli
 */
public class StubTerminal implements Terminal {
  public boolean entered;
  public boolean restored;

  @Override
  public void enterRawMode() {
    entered = true;
  }

  @Override
  public void restore() {
    restored = true;
  }
}

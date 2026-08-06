/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.config.HandlerPaths;

public class HandlerPathsTest extends BaseTest {
  private static final Path HOME = Path.of("/home/dev");

  @Test
  public void blankAndRelativeXDGValuesFallBackToDefaults() {
    // The XDG spec says a value that is not an absolute path must be ignored
    HandlerPaths paths = HandlerPaths.resolve(Map.of("XDG_CONFIG_HOME", "  ",
                                                     "XDG_DATA_HOME", "relative/share",
                                                     "XDG_STATE_HOME", "")::get, HOME);

    Assert.assertEquals(paths.configFile(), Path.of("/home/dev/.config/the-agency-hq/handler.json"));
    Assert.assertEquals(paths.storeRoot(), Path.of("/home/dev/.local/share/the-agency-hq/briefs"));
    Assert.assertEquals(paths.logFile(), Path.of("/home/dev/.local/state/the-agency-hq/handler.log"));
  }

  @Test
  public void defaultsWhenNoXDGVariablesAreSet() {
    HandlerPaths paths = HandlerPaths.resolve(Map.<String, String>of()::get, HOME);

    Assert.assertEquals(paths.configFile(), Path.of("/home/dev/.config/the-agency-hq/handler.json"));
    Assert.assertEquals(paths.storeRoot(), Path.of("/home/dev/.local/share/the-agency-hq/briefs"));
    Assert.assertEquals(paths.logFile(), Path.of("/home/dev/.local/state/the-agency-hq/handler.log"));
  }

  @Test
  public void tokensFileSitsBesideTheConfigFile() {
    HandlerPaths paths = HandlerPaths.resolve(name -> null, Path.of("/home/dev"));

    Assert.assertEquals(paths.tokensFile(), Path.of("/home/dev/.config/the-agency-hq/tokens.json"));
    Assert.assertEquals(paths.tokensFile().getParent(), paths.configFile().getParent());
  }

  @Test
  public void xdgVariablesOverrideDefaults() {
    HandlerPaths paths = HandlerPaths.resolve(Map.of("XDG_CONFIG_HOME", "/etc/xdg",
                                                     "XDG_DATA_HOME", "/var/data",
                                                     "XDG_STATE_HOME", "/var/state")::get, HOME);

    Assert.assertEquals(paths.configFile(), Path.of("/etc/xdg/the-agency-hq/handler.json"));
    Assert.assertEquals(paths.storeRoot(), Path.of("/var/data/the-agency-hq/briefs"));
    Assert.assertEquals(paths.logFile(), Path.of("/var/state/the-agency-hq/handler.log"));
  }
}

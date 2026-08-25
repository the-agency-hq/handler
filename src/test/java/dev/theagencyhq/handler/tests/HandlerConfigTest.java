/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class HandlerConfigTest extends BaseTest {
  @Test
  public void absentExcludeDirectoriesBecomeTheDefaults() {
    HandlerConfig config = config(null, null, 0, 0);
    assertEquals(config.excludeDirectories(), List.of("build", "node_modules", "output", ".*", "Library", "OrbStack", "Desktop", "Documents", "Downloads", "Volumes"));
  }

  @Test
  public void authURLDefaultsAndStripsATrailingSlash() {
    assertEquals(new HandlerConfig(null, null, null, null, 0, 0).authURL(), "https://auth.theagencyhq.dev");
    assertEquals(new HandlerConfig(null, null, null, "  ", 0, 0).authURL(), "https://auth.theagencyhq.dev");
    assertEquals(new HandlerConfig(null, null, null, "http://localhost:9015/", 0, 0).authURL(),
                 "http://localhost:9015");
  }

  @Test(dataProvider = "intervals")
  public void intervalsDefaultAndClamp(int receive, int distribute, int expectedReceive, int expectedDistribute) {
    HandlerConfig config = config(null, null, receive, distribute);
    assertEquals(config.receiveIntervalSeconds(), expectedReceive);
    assertEquals(config.distributeIntervalSeconds(), expectedDistribute);
  }

  @DataProvider
  public Object[][] intervals() {
    return new Object[][]{
        {0, 0, 300, 60},        // absent or zero becomes the default
        {-5, -1, 300, 60},      // negative is treated as absent
        {1, 9, 10, 10},         // anything below 10 is clamped to 10
        {600, 30, 600, 30}      // valid values pass through untouched
    };
  }

  @Test
  public void startDirectoryTildeExpandsAndNormalizes() {
    HandlerConfig config = config("~/dev/../dev/projects", null, 0, 0);
    Path expected = Path.of(System.getProperty("user.home"), "dev", "projects");

    assertEquals(config.startDirectoryPath(), expected);
    assertTrue(config.startDirectoryPath().isAbsolute());
  }

  @Test
  public void suppliedExcludeDirectoriesAreTrimmed() {
    var config = new HandlerConfig(null, List.of("  build  ", "node_modules", " .* "), null, null, 0, 0);
    assertEquals(config.excludeDirectories(), List.of("build", "node_modules", ".*"));
  }

  @Test
  public void theAgencyURLDefaultsAndStripsATrailingSlash() {
    assertEquals(config(null, null, 0, 0).theAgencyURL(), "https://app.theagencyhq.dev");
    assertEquals(config(null, "  ", 0, 0).theAgencyURL(), "https://app.theagencyhq.dev");
    assertEquals(config(null, "http://localhost:8080/", 0, 0).theAgencyURL(), "http://localhost:8080");
    assertEquals(config(null, "http://localhost:8080", 0, 0).theAgencyURL(), "http://localhost:8080");
  }

  @Test
  public void tildeAloneExpandsToHome() {
    assertEquals(config("~", null, 0, 0).startDirectoryPath(), Path.of(System.getProperty("user.home")));
  }

  private HandlerConfig config(String startDirectory, String theAgencyURL, int receive, int distribute) {
    return new HandlerConfig(startDirectory, null, theAgencyURL, null, receive, distribute);
  }
}

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.config.ConfigLoader;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.config.HandlerPaths;

import static org.testng.Assert.*;

public class ConfigLoaderTest extends BaseTest {
  private Path base;

  @Test
  public void environmentOverridesStartDirectory() throws IOException {
    Path configFile = write("""
        {"startDirectory":"/from/file","theAgencyURL":"http://localhost:9011"}
        """);
    Path override = base.resolve("from-env").toAbsolutePath();

    HandlerConfig config = new ConfigLoader(paths(configFile),
                                            Map.of("THE_AGENCY_HQ_START_DIRECTORY", override.toString())::get).load();

    assertEquals(config.startDirectoryPath(), override);
    assertEquals(config.theAgencyURL(), "http://localhost:9011");
  }

  @Test
  public void malformedConfigIsFatal() throws IOException {
    Path configFile = write("{\"startDirectory\": ");

    ConfigLoader loader = new ConfigLoader(paths(configFile), Map.<String, String>of()::get);
    var exception = expectThrows(ConfigLoader.MalformedConfigException.class, loader::load);

    assertTrue(exception.getMessage().contains("[" + configFile + "]"), "Message must name the file: " + exception.getMessage());
  }

  @Test
  public void missingConfigIsWrittenWithDefaultsAndMode0600() throws IOException {
    Path configFile = base.resolve("nested/handler.json");

    HandlerConfig config = new ConfigLoader(paths(configFile), Map.<String, String>of()::get).load();

    assertTrue(Files.isRegularFile(configFile), "The loader must create the file it could not find");
    assertEquals(Files.getPosixFilePermissions(configFile), PosixFilePermissions.fromString("rw-------"));
    assertEquals(config.excludeDirectories(), HandlerConfig.DEFAULT_EXCLUDE_DIRECTORIES);
    assertEquals(config.receiveIntervalSeconds(), 300);
    assertEquals(config.distributeIntervalSeconds(), 60);

    // The written file must itself parse back to the same defaults
    HandlerConfig reloaded = new ConfigLoader(paths(configFile), Map.<String, String>of()::get).load();
    assertEquals(reloaded, config);
  }

  @Test
  public void theDefaultConfigFileIsWrittenToBeEditedByHand() throws IOException {
    Path configFile = base.resolve("handler.json");

    new ConfigLoader(paths(configFile), Map.<String, String>of()::get).load();

    String written = Files.readString(configFile);
    List<String> lines = written.lines().toList();
    assertTrue(lines.size() > 1, "One member per line, not a single dense object: " + written);
    assertTrue(written.endsWith("\n"), "A file a developer opens in an editor ends with a newline");
    assertTrue(lines.stream().anyMatch(line -> line.startsWith("  \"startDirectory\":")),
               "Two-space indent per level. Was: " + written);
  }

  @BeforeMethod
  public void setUp() throws IOException {
    base = Files.createDirectories(Path.of("build/test/config-" + UUID.randomUUID()));
  }

  private HandlerPaths paths(Path configFile) {
    return new HandlerPaths(configFile, base.resolve("briefs"), base.resolve("handler.log"));
  }

  private Path write(String json) throws IOException {
    Path configFile = base.resolve("handler.json");
    Files.writeString(configFile, json);
    return configFile;
  }
}

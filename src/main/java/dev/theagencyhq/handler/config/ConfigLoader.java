/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.config;

import module java.base;

/**
 * Reads {@code handler.json}, creating it with defaults when it is absent. A malformed file is fatal — guessing at a
 * developer's intent here would silently sync from the wrong Agency.
 *
 * @author Brian Pontarelli
 */
public class ConfigLoader {
  public static final String START_DIRECTORY_VARIABLE = "THE_AGENCY_HQ_START_DIRECTORY";
  private static final System.Logger LOG = System.getLogger(ConfigLoader.class.getName());
  private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");

  private final UnaryOperator<String> env;
  private final HandlerPaths paths;

  public ConfigLoader(HandlerPaths paths, UnaryOperator<String> env) {
    this.paths = paths;
    this.env = env;
  }

  public HandlerConfig load() {
    Path configFile = paths.configFile();
    HandlerConfig config;
    if (Files.isRegularFile(configFile)) {
      config = parse(configFile);
    } else {
      config = write(configFile);
    }

    String override = env.apply(START_DIRECTORY_VARIABLE);
    if (override != null && !override.isBlank()) {
      LOG.log(System.Logger.Level.DEBUG, "Start directory overridden by [{0}]", START_DIRECTORY_VARIABLE);
      config = new HandlerConfig(override, config.excludeDirectories(), config.theAgencyURL(), config.authURL(),
                                 config.receiveIntervalSeconds(), config.distributeIntervalSeconds());
    }

    return config;
  }

  private HandlerConfig parse(Path configFile) {
    try {
      return HandlerConfig.fromJSON(Files.readAllBytes(configFile));
    } catch (IOException e) {
      throw new MalformedConfigException("Unable to read the config file [" + configFile + "]", e);
    } catch (RuntimeException e) {
      throw new MalformedConfigException("Unable to parse the config file [" + configFile + "]: " + e.getMessage(), e);
    }
  }

  private HandlerConfig write(Path configFile) {
    // Every field is null or zero, so the compact constructor fills in the complete default set
    HandlerConfig config = new HandlerConfig(null, null, null, null, 0, 0);
    try {
      Files.createDirectories(configFile.getParent());
      // Pretty-printed, and newline-terminated: this file exists to be opened and edited by a developer
      Files.writeString(configFile, config.toPrettyString() + "\n");
      Files.setPosixFilePermissions(configFile, OWNER_READ_WRITE);
      LOG.log(System.Logger.Level.INFO, "Wrote a default config file at [{0}]", configFile);
    } catch (IOException e) {
      throw new MalformedConfigException("Unable to create a default config file at [" + configFile + "]", e);
    }

    return config;
  }

  public static class MalformedConfigException extends RuntimeException {
    public MalformedConfigException(String message) {
      super(message);
    }

    public MalformedConfigException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

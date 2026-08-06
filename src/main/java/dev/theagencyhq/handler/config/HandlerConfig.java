/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.config;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.config.internal.HandlerConfigJSON;

/**
 * The contents of {@code handler.json}. Every value is normalized in the compact constructor, so no caller ever has to
 * defend against a raw form.
 *
 * @author Brian Pontarelli
 */
@JSON
public record HandlerConfig(String startDirectory, List<String> excludeDirectories, String theAgencyURL,
                            String authURL, int receiveIntervalSeconds, int distributeIntervalSeconds) {
  public static final String DEFAULT_AUTH_URL = "https://auth.theagencyhq.dev";
  public static final int DEFAULT_DISTRIBUTE_INTERVAL_SECONDS = 60;
  public static final List<String> DEFAULT_EXCLUDE_DIRECTORIES = List.of("build", "node_modules", "output", ".*", "Library", "OrbStack");
  public static final int DEFAULT_RECEIVE_INTERVAL_SECONDS = 300;
  public static final String DEFAULT_THE_AGENCY_URL = "http://localhost:8080";
  public static final int MINIMUM_INTERVAL_SECONDS = 10;

  public HandlerConfig {
    startDirectory = expandHome(startDirectory);
    excludeDirectories = excludeDirectories == null ? DEFAULT_EXCLUDE_DIRECTORIES
                                                    : excludeDirectories.stream().map(String::trim).toList();
    theAgencyURL = theAgencyURL == null || theAgencyURL.isBlank() ? DEFAULT_THE_AGENCY_URL
                                                                  : stripTrailingSlash(theAgencyURL.trim());
    authURL = authURL == null || authURL.isBlank() ? DEFAULT_AUTH_URL : stripTrailingSlash(authURL.trim());
    receiveIntervalSeconds = interval(receiveIntervalSeconds, DEFAULT_RECEIVE_INTERVAL_SECONDS);
    distributeIntervalSeconds = interval(distributeIntervalSeconds, DEFAULT_DISTRIBUTE_INTERVAL_SECONDS);
  }

  public static HandlerConfig fromJSON(byte[] json) {
    return HandlerConfigJSON.fromJSON(json);
  }

  private static String expandHome(String value) {
    String directory = value == null || value.isBlank() ? "~" : value.trim();
    if (directory.equals("~")) {
      directory = System.getProperty("user.home");
    } else if (directory.startsWith("~/")) {
      directory = System.getProperty("user.home") + directory.substring(1);
    }

    return Path.of(directory).toAbsolutePath().normalize().toString();
  }

  private static int interval(int value, int fallback) {
    if (value <= 0) {
      return fallback;
    }

    return Math.max(value, MINIMUM_INTERVAL_SECONDS);
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /**
   * @return The normalized {@link #startDirectory()} as a {@link Path}.
   */
  public Path startDirectoryPath() {
    return Path.of(startDirectory);
  }

  public String toJSON() {
    return HandlerConfigJSON.toJSON(this);
  }

  /**
   * @return The same JSON as {@link #toJSON()}, indented two spaces per level with one member per line. This is what
   *     the default config file is written with — it is meant to be opened and edited by hand.
   */
  public String toPrettyString() {
    return HandlerConfigJSON.toPrettyString(this);
  }
}

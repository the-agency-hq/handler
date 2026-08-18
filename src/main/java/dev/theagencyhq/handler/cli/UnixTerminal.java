/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;

/**
 * The real {@link Terminal}, driven by {@code stty} with the JVM's own stdin so the mode change lands on the
 * controlling terminal. The JDK has no API for terminal modes, and {@code stty} is present on every platform the
 * Handler runs on (macOS and Linux).
 *
 * @author Brian Pontarelli
 */
public class UnixTerminal implements Terminal {
  private String saved;

  @Override
  public void enterRawMode() throws IOException {
    // "stty -g" emits the full current configuration as one token that a later stty call accepts verbatim
    saved = stty("-g");
    stty("-icanon", "-echo", "min", "1", "time", "0");
  }

  @Override
  public void restore() {
    if (saved == null) {
      return;
    }

    try {
      stty(saved);
    } catch (IOException e) {
      // The shell resets the terminal on exit anyway; failing the command over this would bury the real outcome
    } finally {
      saved = null;
    }
  }

  private String stty(String... arguments) throws IOException {
    List<String> command = new ArrayList<>(List.of("stty"));
    command.addAll(List.of(arguments));

    Process process = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.INHERIT).start();
    String output;
    try (InputStream stdout = process.getInputStream()) {
      output = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("stty did not complete");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("stty was interrupted");
    }

    if (process.exitValue() != 0) {
      throw new IOException("stdin is not an interactive terminal");
    }

    return output;
  }
}

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * Answers every command with {@link #exitCode}, [id -u] with uid 501, and records everything that ran.
 *
 * @author Brian Pontarelli
 */
public class RecordingExecutor implements ProcessCommand.Executor {
  List<String> commands = new ArrayList<>();
  int exitCode;

  @Override
  public ProcessCommand.ExecutionResult execute(String... command) {
    String joined = String.join(" ", command);
    commands.add(joined);
    return new ProcessCommand.ExecutionResult(exitCode, joined.equals("id -u") ? "501\n" : "");
  }
}

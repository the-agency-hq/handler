/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * Base class for the subcommands that manage the installed daemon by running external commands through
 * {@link ProcessBuilder} — launchctl and pkgutil on macOS, systemctl and pkill on Linux. Failures travel inside the
 * {@link ExecutionResult} each run returns rather than being thrown, so every step still runs and whatever went wrong
 * can be fixed and the command run again.
 *
 * @author Brian Pontarelli
 */
public abstract class ProcessCommand {
  static final String DAEMON_LABEL = "dev.theagencyhq.handler.daemon";
  static final String SERVICE = "the-agency-hq-handler";
  static final String TRAY_LABEL = "dev.theagencyhq.handler.tray";

  protected final Executor executor;
  protected final Path home;
  protected final boolean macOS;
  protected final PrintStream out;
  protected final HandlerPaths paths;

  protected ProcessCommand(HandlerPaths paths, Path home, boolean macOS, Executor executor, PrintStream out) {
    this.paths = paths;
    this.home = home;
    this.macOS = macOS;
    this.executor = executor;
    this.out = out;
  }

  /**
   * The real {@link Executor}, which {@code Main} injects as a method reference. Standard error is discarded —
   * launchctl and pkgutil complain on stderr about work that is already done, and the exit code carries everything a
   * subcommand needs.
   *
   * @param command The command and its arguments.
   * @return The exit code and captured standard output.
   * @throws IOException When the command cannot be started or does not finish within thirty seconds.
   * @throws InterruptedException When the wait for the command is interrupted.
   */
  public static ExecutionResult execute(String... command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    String stdout;
    try (InputStream stream = process.getInputStream()) {
      stdout = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("[" + String.join(" ", command) + "] timed out");
    }

    return new ExecutionResult(process.exitValue(), stdout);
  }

  /**
   * The launchd property list the macOS installer writes for the daemon.
   *
   * @return The plist path.
   */
  protected Path daemonPlist() {
    return home.resolve(Path.of("Library", "LaunchAgents", DAEMON_LABEL + ".plist"));
  }

  /**
   * Whether the daemon is installed under the platform supervisor — the launchd plist on macOS, the systemd user unit
   * on Linux. A development build run straight from the repository has neither, so there is nothing for the service
   * commands to manage.
   *
   * @return True when the supervisor knows the daemon.
   */
  protected boolean installed() {
    return Files.exists(macOS ? daemonPlist() : unitFile());
  }

  /**
   * Prints why the command has nothing to manage and how to run the daemon anyway.
   *
   * @return The exit code 1.
   */
  protected int notInstalled() {
    out.println("The Handler daemon is not installed as a service. Run [handler daemon] to run it in the foreground.");
    return 1;
  }

  /**
   * Prints the failures the results carry, or the success line when every result is clean.
   *
   * @param success The line to print when nothing failed.
   * @param results The results of every command the subcommand ran.
   * @return The exit code: 1 when anything failed, 0 otherwise.
   */
  protected int report(String success, ExecutionResult... results) {
    List<String> failures = Stream.of(results).filter(ExecutionResult::failed).map(ExecutionResult::failure).toList();
    if (!failures.isEmpty()) {
      failures.forEach(out::println);
      return 1;
    }

    out.println(success);
    return 0;
  }

  /**
   * Runs one command whose non-zero exit is a real failure. The counterpart to {@link #runProcess}, whose callers
   * interpret or ignore the exit code themselves.
   *
   * @param command The command and its arguments.
   * @return The result, failed when the command could not run or exited non-zero.
   */
  protected ExecutionResult require(String... command) {
    ExecutionResult result = runProcess(command);
    if (!result.failed() && result.exitCode() != 0) {
      return new ExecutionResult(result.exitCode(), result.stdout(),
                                 "[" + String.join(" ", command) + "] exited [" + result.exitCode() + "]");
    }

    return result;
  }

  /**
   * Runs one command, converting a failure to launch or finish it into a failed result rather than an exception. The
   * exit code is the caller's to interpret.
   *
   * @param command The command and its arguments.
   * @return The result, failed only when the command could not be run at all.
   */
  protected ExecutionResult runProcess(String... command) {
    try {
      return executor.execute(command);
    } catch (IOException e) {
      return ExecutionResult.failed("Unable to run [" + String.join(" ", command) + "]: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ExecutionResult.failed("Unable to run [" + String.join(" ", command) + "]: interrupted");
    }
  }

  /**
   * Looks up the uid launchctl needs to address the per-user domain.
   *
   * @return The result of [id -u] with the uid, stripped of its trailing newline, as the stdout — or a failed result
   *     when the uid could not be determined.
   */
  protected ExecutionResult uid() {
    ExecutionResult id = runProcess("id", "-u");
    if (id.failed()) {
      return id;
    }

    if (id.exitCode() != 0) {
      return new ExecutionResult(id.exitCode(), id.stdout(),
                                 "Unable to determine the current uid: [id -u] exited [" + id.exitCode() + "]");
    }

    return new ExecutionResult(id.exitCode(), id.stdout().strip());
  }

  /**
   * The systemd user unit the Linux installer writes for the daemon, under the same XDG configuration base the
   * installer resolved.
   *
   * @return The unit path.
   */
  protected Path unitFile() {
    return paths.configBase().resolve("systemd/user/" + SERVICE + ".service");
  }

  /**
   * One finished command — or the failure that kept it from finishing or succeeding.
   *
   * @param exitCode The command's exit code, or -1 when it never produced one.
   * @param stdout   Everything the command printed to standard output.
   * @param failure  Why the command failed, or null when it did not.
   */
  public record ExecutionResult(int exitCode, String stdout, String failure) {
    public ExecutionResult(int exitCode, String stdout) {
      this(exitCode, stdout, null);
    }

    /**
     * A command that never produced an exit code.
     *
     * @param failure Why the command could not run.
     * @return The failed result.
     */
    public static ExecutionResult failed(String failure) {
      return new ExecutionResult(-1, "", failure);
    }

    public boolean failed() {
      return failure != null;
    }
  }

  /**
   * Runs a system command and waits for it — the seam that keeps launchctl and systemctl out of the tests.
   */
  @FunctionalInterface
  public interface Executor {
    ExecutionResult execute(String... command) throws IOException, InterruptedException;
  }
}

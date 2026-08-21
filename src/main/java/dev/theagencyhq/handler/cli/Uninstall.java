/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;

import dev.theagencyhq.handler.config.HandlerPaths;

/**
 * The {@code uninstall} subcommand. Both platforms install per-user into the home directory, so the uninstall is the
 * same shape on each: stop the daemon and tray, remove what the installer placed, then remove the files the Handler
 * wrote while running. On macOS that is the launch agents, the app bundle, and the pkg receipt; on Linux the systemd
 * user unit, the desktop entries, and the payload under {@code ~/.local/lib}. The configuration directory is shared
 * with the other Agency HQ tools, so only {@code handler.json} and {@code tokens.json} are deleted from it — never
 * the directory itself.
 *
 * <p>Every step is best-effort: a failing step is recorded and the rest still run, so a half-removed install can be
 * fixed and the command run again to completion.
 *
 * @author Brian Pontarelli
 */
public class Uninstall {
  static final String APP_BUNDLE = "Applications/The Agency Handler.app";
  static final List<String> LAUNCH_AGENT_LABELS = List.of("dev.theagencyhq.handler.daemon",
                                                          "dev.theagencyhq.handler.tray");
  static final String PACKAGE_ID = "dev.theagencyhq.handler";
  static final String SERVICE = "the-agency-hq-handler";

  private final Executor executor;
  private final Path home;
  private final InputStream in;
  private final boolean macOS;
  private final PrintStream out;
  private final HandlerPaths paths;

  public Uninstall(HandlerPaths paths, Path home, boolean macOS, Executor executor, InputStream in, PrintStream out) {
    this.paths = paths;
    this.home = home;
    this.macOS = macOS;
    this.executor = executor;
    this.in = in;
    this.out = out;
  }

  /**
   * The real {@link Executor}, which {@code Main} injects as a method reference. Standard error is discarded —
   * launchctl and pkgutil complain on stderr about work that is already done, and the exit code carries everything the
   * uninstall needs.
   *
   * @param command The command and its arguments.
   * @return The exit code and captured standard output.
   * @throws IOException When the command cannot be started or does not finish within thirty seconds.
   * @throws InterruptedException When the wait for the command is interrupted.
   */
  public static Execution execute(String... command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    String stdout;
    try (InputStream stream = process.getInputStream()) {
      stdout = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("[" + String.join(" ", command) + "] timed out");
    }

    return new Execution(process.exitValue(), stdout);
  }

  public int run(boolean yes) {
    if (!yes && !confirm()) {
      out.println("Uninstall cancelled.");
      return 0;
    }

    List<String> failures = new ArrayList<>();
    if (macOS) {
      uninstallMacOS(failures);
    } else {
      uninstallLinux(failures);
    }

    // Only the Handler's own two files — the configuration directory is shared with the other Agency HQ tools
    delete(failures, paths.configFile());
    delete(failures, paths.tokensFile());

    // The data and state directories are the Handler's alone, but another tool may adopt them someday, so they are
    // only removed once they are empty. The log is matched by prefix — FileHandler rotates it into [handler.log.0]
    // through [handler.log.2] and drops [.lck] files next to them.
    deleteTree(failures, paths.storeRoot());
    deleteByPrefix(failures, paths.logFile().getParent(), paths.logFile().getFileName().toString());
    delete(failures, paths.socketFile());
    deleteIfEmpty(paths.storeRoot().getParent());
    deleteIfEmpty(paths.logFile().getParent());

    if (!failures.isEmpty()) {
      failures.forEach(out::println);
      out.println("Some steps failed. Fix the problems above and run [handler uninstall] again.");
      return 1;
    }

    out.println("Stopped the daemon and tray, and removed the Handler application, the [handler] command, and the"
        + " stored Briefs, logs, and tokens.");
    out.println("Left [" + paths.configFile().getParent() + "] in place for other Agency HQ tools.");
    return 0;
  }

  private boolean confirm() {
    out.println("This stops the Handler daemon and tray, and deletes the app, the [handler] command, the stored"
        + " Briefs, the logs, and your login tokens.");
    out.println("Other Agency HQ files in [" + paths.configFile().getParent() + "] are left in place.");
    out.print("Uninstall The Agency Handler? [y/N] ");

    String answer;
    try {
      answer = readLine();
    } catch (IOException e) {
      return false;
    }

    return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
  }

  private void delete(List<String> failures, Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      failures.add("Unable to delete [" + path + "]: " + e.getMessage());
    }
  }

  private void deleteByPrefix(List<String> failures, Path directory, String prefix) {
    if (!Files.isDirectory(directory)) {
      return;
    }

    try (Stream<Path> entries = Files.list(directory)) {
      for (Path entry : entries.filter(e -> e.getFileName().toString().startsWith(prefix)).toList()) {
        delete(failures, entry);
      }
    } catch (IOException e) {
      failures.add("Unable to list [" + directory + "]: " + e.getMessage());
    }
  }

  /**
   * Removes a directory only once it is empty. Best-effort by design: a leftover empty directory is not worth failing
   * the uninstall over, and a non-empty one means another tool owns files in it.
   */
  private void deleteIfEmpty(Path directory) {
    try {
      Files.deleteIfExists(directory);
    } catch (IOException e) {
      // Leave it for whatever still owns files in it
    }
  }

  private void deleteTree(List<String> failures, Path root) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }

    try (Stream<Path> tree = Files.walk(root)) {
      List<Path> deepestFirst = tree.sorted(Comparator.reverseOrder()).toList();
      for (Path path : deepestFirst) {
        Files.deleteIfExists(path);
      }
    } catch (IOException e) {
      failures.add("Unable to delete [" + root + "]: " + e.getMessage());
    }
  }

  /**
   * Reads one line without a BufferedReader, matching {@code Init}, so nothing is read ahead of the confirmation.
   *
   * @return The line without its terminator, trimmed, or null when the input ended before any line arrived.
   */
  private String readLine() throws IOException {
    StringBuilder line = new StringBuilder();
    int c;
    while ((c = in.read()) != -1 && c != '\n') {
      line.append((char) c);
    }

    if (c == -1 && line.isEmpty()) {
      return null;
    }

    return line.toString().trim();
  }

  /**
   * Runs one command, converting a failure to launch or finish it into a recorded message rather than an exception.
   * The exit code is the caller's to interpret.
   *
   * @return The execution, or null when the command could not be run at all.
   */
  private Execution runProcess(List<String> failures, String... command) {
    try {
      return executor.execute(command);
    } catch (IOException e) {
      failures.add("Unable to run [" + String.join(" ", command) + "]: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failures.add("Unable to run [" + String.join(" ", command) + "]: interrupted");
    }

    return null;
  }

  /**
   * Undoes install.sh: stops and disables the systemd user unit, kills the tray, and removes the unit, the desktop
   * entries, the icon, the payload, and the CLI symlink. The unit and desktop entries are removed from the XDG bases
   * {@link HandlerPaths} resolved, matching where install.sh wrote them when {@code XDG_CONFIG_HOME} or
   * {@code XDG_DATA_HOME} is set. Non-zero exits are ignored — [systemctl disable] fails when the unit was never
   * enabled and [pkill] when the tray is not running, and neither leaves anything to stop.
   *
   * <p>Every external command runs before the payload is deleted: this process runs on the payload's own JVM, and
   * deleting the payload takes the runtime's spawn helper with it, after which no new process can be started.
   */
  private void uninstallLinux(List<String> failures) {
    runProcess(failures, "systemctl", "--user", "disable", "--now", SERVICE);
    // The tray is autostarted by the desktop session rather than systemd
    runProcess(failures, "pkill", "-x", "handler-tray");

    // The unit file goes first so the reload sees it gone
    delete(failures, paths.configBase().resolve("systemd/user/" + SERVICE + ".service"));
    runProcess(failures, "systemctl", "--user", "daemon-reload");

    delete(failures, paths.configBase().resolve("autostart/" + SERVICE + ".desktop"));
    delete(failures, paths.dataBase().resolve("applications/" + SERVICE + ".desktop"));
    delete(failures, paths.dataBase().resolve("icons/hicolor/256x256/apps/" + SERVICE + ".png"));
    deleteTree(failures, home.resolve(".local/lib/the-agency-hq/handler"));
    deleteIfEmpty(home.resolve(".local/lib/the-agency-hq"));
    delete(failures, home.resolve(".local/bin/handler"));
  }

  /**
   * Undoes the pkg postinstall: boots the launch agents out before deleting anything, so launchd does not restart the
   * daemon mid-removal and the daemon does not recreate the socket and log after they are deleted, then removes the
   * plists, the pkg receipt, the app bundle, and the CLI symlink. A non-zero bootout exit only means the agent was
   * not loaded, and a non-zero pkgutil exit that there is no receipt — which is what a development install looks
   * like — so their exit codes are ignored.
   *
   * <p>Every external command runs before the app bundle is deleted: this process runs on the bundle's own JVM, and
   * deleting the bundle takes the runtime's spawn helper with it, after which no new process can be started.
   */
  private void uninstallMacOS(List<String> failures) {
    Execution id = runProcess(failures, "id", "-u");
    String uid = id != null && id.exitCode() == 0 ? id.stdout().strip() : null;
    if (id != null && id.exitCode() != 0) {
      failures.add("Unable to determine the current uid: [id -u] exited [" + id.exitCode() + "]");
    }

    for (String label : LAUNCH_AGENT_LABELS) {
      if (uid != null) {
        runProcess(failures, "launchctl", "bootout", "gui/" + uid + "/" + label);
      }

      delete(failures, home.resolve(Path.of("Library", "LaunchAgents", label + ".plist")));
    }

    // The pkg installs into the home directory, so its receipt lives in the user domain rather than at [/]
    runProcess(failures, "pkgutil", "--volume", home.toString(), "--forget", PACKAGE_ID);

    deleteTree(failures, home.resolve(APP_BUNDLE));
    delete(failures, home.resolve(Path.of(".local", "bin", "handler")));
  }

  /**
   * One finished command.
   *
   * @param exitCode The command's exit code.
   * @param stdout   Everything the command printed to standard output.
   */
  public record Execution(int exitCode, String stdout) {
  }

  /**
   * Runs a system command and waits for it — the seam that keeps launchctl and pkgutil out of the tests.
   */
  @FunctionalInterface
  public interface Executor {
    Execution execute(String... command) throws IOException, InterruptedException;
  }
}

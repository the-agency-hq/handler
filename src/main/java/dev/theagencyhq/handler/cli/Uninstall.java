/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

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
public class Uninstall extends ProcessCommand {
  static final String APP_BUNDLE = "Applications/The Agency Handler.app";
  static final List<String> LAUNCH_AGENT_LABELS = List.of(DAEMON_LABEL, TRAY_LABEL);
  static final String PACKAGE_ID = "dev.theagencyhq.handler";

  private final InputStream in;

  public Uninstall(HandlerPaths paths, Path home, boolean macOS, Executor executor, InputStream in, PrintStream out) {
    super(paths, home, macOS, executor, out);
    this.in = in;
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
    delete(failures, paths.stateFile());
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
   * Runs one command whose exit code is ignored, recording only a failure to run it at all.
   */
  private void runProcess(List<String> failures, String... command) {
    ExecutionResult result = runProcess(command);
    if (result.failed()) {
      failures.add(result.failure());
    }
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
    delete(failures, unitFile());
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
    ExecutionResult uid = uid();
    if (uid.failed()) {
      failures.add(uid.failure());
    }

    for (String label : LAUNCH_AGENT_LABELS) {
      if (!uid.failed()) {
        runProcess(failures, "launchctl", "bootout", "gui/" + uid.stdout() + "/" + label);
      }

      delete(failures, home.resolve(Path.of("Library", "LaunchAgents", label + ".plist")));
    }

    // The pkg installs into the home directory, so its receipt lives in the user domain rather than at [/]
    runProcess(failures, "pkgutil", "--volume", home.toString(), "--forget", PACKAGE_ID);

    deleteTree(failures, home.resolve(APP_BUNDLE));
    delete(failures, home.resolve(Path.of(".local", "bin", "handler")));
  }
}

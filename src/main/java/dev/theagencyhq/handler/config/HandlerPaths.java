/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.config;

import module java.base;

/**
 * The four filesystem locations the Handler owns, resolved once in {@code Main} and injected everywhere else so tests
 * never touch the real home directory.
 *
 * @author Brian Pontarelli
 */
public record HandlerPaths(Path configFile, Path tokensFile, Path storeRoot, Path logFile) {
  private static final String VENDOR_DIRECTORY = "the-agency-hq";

  /**
   * Resolves the paths from the process environment. This is the only place in the Handler that reads an environment
   * variable.
   *
   * @return The resolved paths.
   */
  public static HandlerPaths fromEnvironment() {
    return resolve(System::getenv, Path.of(System.getProperty("user.home")));
  }

  /**
   * Resolves the paths against an arbitrary environment lookup and home directory. This is the injection seam that
   * makes XDG resolution testable — Java cannot modify its own process environment.
   *
   * @param env  Looks up an environment variable by name, returning null when it is unset.
   * @param home The user's home directory.
   * @return The resolved paths.
   */
  public static HandlerPaths resolve(UnaryOperator<String> env, Path home) {
    Path config = base(env, "XDG_CONFIG_HOME", home.resolve(".config"));
    Path data = base(env, "XDG_DATA_HOME", home.resolve(Path.of(".local", "share")));
    Path state = base(env, "XDG_STATE_HOME", home.resolve(Path.of(".local", "state")));

    return new HandlerPaths(config.resolve(VENDOR_DIRECTORY).resolve("handler.json"),
                            config.resolve(VENDOR_DIRECTORY).resolve("tokens.json"),
                            data.resolve(VENDOR_DIRECTORY).resolve("briefs"),
                            state.resolve(VENDOR_DIRECTORY).resolve("handler.log"));
  }

  private static Path base(UnaryOperator<String> env, String variable, Path fallback) {
    String value = env.apply(variable);
    if (value == null || value.isBlank()) {
      return fallback;
    }

    // The XDG spec requires that a value which is not an absolute path be ignored entirely
    Path path = Path.of(value.trim());
    return path.isAbsolute() ? path : fallback;
  }

  /**
   * The XDG configuration base the config file was resolved under — {@code XDG_CONFIG_HOME} when it is set to an
   * absolute path, otherwise {@code ~/.config}. Derived rather than stored so the Linux uninstall removes the systemd
   * unit and autostart entry from the same base the installer wrote them to.
   *
   * @return The configuration base directory.
   */
  public Path configBase() {
    return configFile.getParent().getParent();
  }

  /**
   * The XDG data base the Brief store was resolved under — {@code XDG_DATA_HOME} when it is set to an absolute path,
   * otherwise {@code ~/.local/share}.
   *
   * @return The data base directory.
   */
  public Path dataBase() {
    return storeRoot.getParent().getParent();
  }

  /**
   * The Unix domain socket the daemon serves tray status over. Derived from the log location so it lives in the state
   * directory, where an external tray process can find it without any configuration.
   *
   * @return The socket path.
   */
  public Path socketFile() {
    return logFile.getParent().resolve("handler.sock");
  }

  /**
   * The file the daemon writes after every distribute cycle and {@code handler status} reads. Derived from the log
   * location so it lives in the state directory.
   *
   * @return The state file path.
   */
  public Path stateFile() {
    return logFile.getParent().resolve("state.json");
  }
}

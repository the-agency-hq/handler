/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.log;

import module java.base;
import module java.logging;
import module dev.theagencyhq.handler;

import java.util.logging.Formatter;

/**
 * Configures the JUL backend that sits behind {@code System.Logger}: one line per record to stderr so launchd and
 * systemd capture it, plus a size-capped rotating file.
 *
 * @author Brian Pontarelli
 */
public final class Logging {
  public static final int LOG_FILE_COUNT = 3;
  public static final int LOG_FILE_LIMIT = 5 * 1024 * 1024;
  public static final String LEVEL_PROPERTY = "handler.log.level";
  /**
   * The embedded HTTP server behind {@code handler login} narrates its own startup at INFO — including a port line
   * that reports the configured {@code 0} rather than the ephemeral port it actually bound, which is worse than
   * noise. None of it belongs on a developer's terminal in the middle of a login, so the package is pinned to
   * WARNING and above.
   *
   * <p>The reference is held statically because {@code LogManager} only weakly retains loggers; a collected logger
   * silently reverts to the inherited level.
   */
  private static final Logger HTTP_LOGGER = Logger.getLogger("org.lattejava.http");

  private Logging() {
  }

  /**
   * @param paths The resolved Handler paths, used for the log file location.
   */
  public static void configure(HandlerPaths paths) {
    Logger root = Logger.getLogger("");
    for (java.util.logging.Handler existing : root.getHandlers()) {
      root.removeHandler(existing);
    }

    Formatter formatter = new OneLineFormatter();

    ConsoleHandler console = new ConsoleHandler();      // ConsoleHandler writes to System.err
    console.setFormatter(formatter);
    console.setLevel(Level.ALL);
    root.addHandler(console);

    try {
      Files.createDirectories(paths.logFile().getParent());
      FileHandler file = new FileHandler(paths.logFile().toString(), LOG_FILE_LIMIT, LOG_FILE_COUNT, true);
      file.setFormatter(formatter);
      file.setLevel(Level.ALL);
      root.addHandler(file);
    } catch (IOException e) {
      // stderr logging still works, so this is degraded rather than fatal
      root.log(Level.WARNING, "Unable to open the log file [" + paths.logFile() + "]", e);
    }

    root.setLevel(level());
    HTTP_LOGGER.setLevel(Level.WARNING);
  }

  private static Level level() {
    String configured = System.getProperty(LEVEL_PROPERTY);
    if (configured == null || configured.isBlank()) {
      return Level.INFO;
    }

    try {
      return Level.parse(configured.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return Level.INFO;
    }
  }

  private static class OneLineFormatter extends Formatter {
    @Override
    public String format(LogRecord record) {
      StringBuilder line = new StringBuilder(128);
      line.append(DateTimeFormatter.ISO_INSTANT.format(record.getInstant()))
          .append(' ')
          .append(record.getLevel().getName())
          .append(" [")
          .append(record.getLoggerName() == null ? "" : record.getLoggerName())
          .append("] ")
          .append(formatMessage(record))
          .append(System.lineSeparator());

      if (record.getThrown() != null) {
        StringWriter writer = new StringWriter();
        record.getThrown().printStackTrace(new PrintWriter(writer));
        line.append(writer);
      }

      return line.toString();
    }
  }
}

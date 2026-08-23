/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module java.logging;
import module dev.theagencyhq.handler;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.*;

/**
 * {@code configure} mutates the global JUL root logger, so every test snapshots it in {@link #setUp} and restores it
 * in {@link #tearDown} — otherwise one test's handlers or level would leak into its neighbours.
 *
 * @author Brian Pontarelli
 */
public class LoggingTest extends BaseTest {
  private Path base;
  private String originalLevelProperty;
  private java.util.logging.Handler[] originalRootHandlers;
  private Level originalRootLevel;

  @Test
  public void aLoggedThrowableReachesTheFileWithItsStackTrace() throws IOException {
    Path logFile = base.resolve("handler.log");
    Logging.configure(paths(logFile));

    System.Logger log = System.getLogger("LoggingTest.throwing");
    log.log(System.Logger.Level.ERROR, "failure", new RuntimeException("boom"));
    flushHandlers();

    String content = Files.readString(writtenLogFile(logFile));
    assertTrue(content.contains("RuntimeException"), "Content was: " + content);
    assertTrue(content.contains("boom"), "Content was: " + content);
  }

  @Test
  public void anUnopenableLogFileDegradesRatherThanThrowing() throws IOException {
    // The PARENT is an existing regular file, so Files.createDirectories(parent) must fail
    Path blockingParent = Files.createFile(base.resolve("blocking-parent"));
    Path logFile = blockingParent.resolve("handler.log");

    // Must return normally rather than throwing - stderr logging must still work when the file cannot be opened
    Logging.configure(paths(logFile));

    assertFalse(Files.exists(writtenLogFile(logFile)), "The log file must never have been created");
    assertEquals(java.util.logging.Logger.getLogger("").getHandlers().length, 1, "Only the console handler should be present");
  }

  @Test
  public void configureCreatesTheLogFileAndALoggedMessageReachesItWithLevelAndLoggerName() throws IOException {
    Path logFile = base.resolve("handler.log");
    Logging.configure(paths(logFile));

    Path written = writtenLogFile(logFile);
    assertTrue(Files.exists(written), "configure must create the log file");

    System.Logger log = System.getLogger("LoggingTest.probe");
    log.log(System.Logger.Level.INFO, "probe message");
    flushHandlers();

    String content = Files.readString(written);
    assertTrue(content.contains("INFO"), "Content was: " + content);
    assertTrue(content.contains("LoggingTest.probe"), "Content was: " + content);
    assertTrue(content.contains("probe message"), "Content was: " + content);
  }

  @Test
  public void consoleHandlerWritesToStandardError() throws IOException {
    // ConsoleHandler captures System.err in its constructor, so it must be swapped BEFORE configure() runs
    PrintStream originalErr = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
      Logging.configure(paths(base.resolve("handler.log")));

      System.getLogger("LoggingTest.console").log(System.Logger.Level.INFO, "to stderr");
      flushHandlers();
    } finally {
      System.setErr(originalErr);
    }

    assertTrue(captured.toString(StandardCharsets.UTF_8).contains("to stderr"),
                      "The console handler must write to stderr");
  }

  @Test
  public void defaultLevelSuppressesDebugMessages() throws IOException {
    Path logFile = base.resolve("handler.log");
    Logging.configure(paths(logFile));

    System.Logger log = System.getLogger("LoggingTest.suppressed");
    log.log(System.Logger.Level.DEBUG, "should not appear");
    flushHandlers();

    String content = Files.readString(writtenLogFile(logFile));
    assertFalse(content.contains("should not appear"), "Content was: " + content);
  }

  @Test
  public void handlerLogLevelPropertyEnablesDebugMessages() throws IOException {
    System.setProperty(Logging.LEVEL_PROPERTY, "FINE");
    Path logFile = base.resolve("handler.log");
    Logging.configure(paths(logFile));

    System.Logger log = System.getLogger("LoggingTest.debug");
    log.log(System.Logger.Level.DEBUG, "debug message");
    flushHandlers();

    String content = Files.readString(writtenLogFile(logFile));
    assertTrue(content.contains("debug message"), "Content was: " + content);
  }

  @BeforeMethod
  public void setUp() throws IOException {
    base = Files.createDirectories(Path.of("build/test/logging-" + UUID.randomUUID()).toAbsolutePath());

    java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
    originalLevelProperty = System.getProperty(Logging.LEVEL_PROPERTY);
    originalRootHandlers = root.getHandlers();
    originalRootLevel = root.getLevel();
  }

  @AfterMethod
  public void tearDown() {
    java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
    for (java.util.logging.Handler handler : root.getHandlers()) {
      handler.close();
      root.removeHandler(handler);
    }

    for (java.util.logging.Handler handler : originalRootHandlers) {
      root.addHandler(handler);
    }

    root.setLevel(originalRootLevel);
    if (originalLevelProperty == null) {
      System.clearProperty(Logging.LEVEL_PROPERTY);
    } else {
      System.setProperty(Logging.LEVEL_PROPERTY, originalLevelProperty);
    }
  }

  private void flushHandlers() {
    for (java.util.logging.Handler handler : java.util.logging.Logger.getLogger("").getHandlers()) {
      handler.flush();
    }
  }

  private HandlerPaths paths(Path logFile) {
    return new HandlerPaths(base.resolve("handler.json"), base.resolve("tokens.json"), base.resolve("store"), logFile);
  }

  private Path writtenLogFile(Path logFile) {
    // Logging's FileHandler pattern has no %g and a count above 1, so JUL appends its own generation suffix
    return Path.of(logFile + ".0");
  }
}

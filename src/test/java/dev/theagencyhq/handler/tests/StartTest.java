/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.*;

public class StartTest extends BaseTest {
  private RecordingExecutor executor;
  private Path home;
  private ByteArrayOutputStream output;
  private HandlerPaths paths;

  @Test
  public void aNonZeroBootstrapExitOnlyMeansTheDaemonIsAlreadyRunning() throws IOException {
    installMacOS();
    executor = new RecordingExecutor() {
      @Override
      public ProcessCommand.ExecutionResult execute(String... command) {
        ProcessCommand.ExecutionResult execution = super.execute(command);
        return command[0].equals("launchctl") ? new ProcessCommand.ExecutionResult(5, "") : execution;
      }
    };

    assertEquals(start(true).run(), 0, "Output was: " + output);
    assertTrue(output.toString().contains("Started"), "Output was: " + output);
  }

  @Test
  public void linuxStartReportsARealFailure() throws IOException {
    installLinux();
    executor.exitCode = 1;

    assertEquals(start(false).run(), 1);
    assertTrue(output.toString().contains("exited [1]"), "Output was: " + output);
  }

  @Test
  public void linuxStartRunsSystemctl() throws IOException {
    installLinux();

    assertEquals(start(false).run(), 0, "Output was: " + output);

    assertEquals(executor.commands, List.of("systemctl --user start the-agency-hq-handler"));
    assertTrue(output.toString().contains("Started"), "Output was: " + output);
  }

  @Test
  public void macOSStartBootstrapsTheDaemon() throws IOException {
    installMacOS();

    assertEquals(start(true).run(), 0, "Output was: " + output);

    assertEquals(executor.commands, List.of("id -u", "launchctl bootstrap gui/501 " + plist()));
    assertTrue(output.toString().contains("Started"), "Output was: " + output);
  }

  @BeforeMethod
  public void setUp() {
    executor = new RecordingExecutor();
    home = base.resolve("home");
    output = new ByteArrayOutputStream();
    paths = HandlerPaths.resolve(_ -> null, home);
  }

  @Test
  public void startWithoutAnInstalledServiceExplainsItself() {
    assertEquals(start(true).run(), 1);
    assertTrue(output.toString().contains("not installed"), "Output was: " + output);
    assertTrue(executor.commands.isEmpty(), "Nothing may run without an install. Ran: " + executor.commands);
  }

  private void installLinux() throws IOException {
    Files.createDirectories(paths.configBase().resolve("systemd/user"));
    Files.writeString(paths.configBase().resolve("systemd/user/the-agency-hq-handler.service"), "[Unit]");
  }

  private void installMacOS() throws IOException {
    Files.createDirectories(plist().getParent());
    Files.writeString(plist(), "<plist/>");
  }

  private Path plist() {
    return home.resolve("Library/LaunchAgents/dev.theagencyhq.handler.daemon.plist");
  }

  private Start start(boolean macOS) {
    return new Start(paths, home, macOS, executor, new PrintStream(output, true));
  }
}

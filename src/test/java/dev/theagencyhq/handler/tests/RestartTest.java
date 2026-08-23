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

public class RestartTest extends BaseTest {
  private RecordingExecutor executor;
  private Path home;
  private ByteArrayOutputStream output;
  private HandlerPaths paths;

  @Test
  public void aFailedBootstrapAfterTheBootoutIsARealFailure() throws IOException {
    // The bootout makes "already loaded" impossible, so unlike [handler start] the bootstrap exit is meaningful
    installMacOS();
    executor = new RecordingExecutor() {
      @Override
      public ProcessCommand.ExecutionResult execute(String... command) {
        ProcessCommand.ExecutionResult execution = super.execute(command);
        return command[0].equals("launchctl") && command[1].equals("bootstrap")
            ? new ProcessCommand.ExecutionResult(5, "") : execution;
      }
    };

    assertEquals(restart(true).run(), 1);
    assertTrue(output.toString().contains("exited [5]"), "Output was: " + output);
  }

  @Test
  public void aNonZeroBootoutExitStillRestartsAStoppedDaemon() throws IOException {
    installMacOS();
    executor = new RecordingExecutor() {
      @Override
      public ProcessCommand.ExecutionResult execute(String... command) {
        ProcessCommand.ExecutionResult execution = super.execute(command);
        return command[0].equals("launchctl") && command[1].equals("bootout")
            ? new ProcessCommand.ExecutionResult(3, "") : execution;
      }
    };

    assertEquals(restart(true).run(), 0, "Output was: " + output);
    assertTrue(output.toString().contains("Restarted"), "Output was: " + output);
  }

  @Test
  public void linuxRestartRunsSystemctl() throws IOException {
    installLinux();

    assertEquals(restart(false).run(), 0, "Output was: " + output);

    assertEquals(executor.commands, List.of("systemctl --user restart the-agency-hq-handler"));
    assertTrue(output.toString().contains("Restarted"), "Output was: " + output);
  }

  @Test
  public void macOSRestartBootsOutThenBootstraps() throws IOException {
    installMacOS();

    assertEquals(restart(true).run(), 0, "Output was: " + output);

    assertEquals(executor.commands, List.of("id -u", "launchctl bootout gui/501/dev.theagencyhq.handler.daemon",
                                            "launchctl bootstrap gui/501 " + plist()));
    assertTrue(output.toString().contains("Restarted"), "Output was: " + output);
  }

  @Test
  public void restartWithoutAnInstalledServiceExplainsItself() {
    assertEquals(restart(true).run(), 1);
    assertTrue(output.toString().contains("not installed"), "Output was: " + output);
    assertTrue(executor.commands.isEmpty(), "Nothing may run without an install. Ran: " + executor.commands);
  }

  @BeforeMethod
  public void setUp() {
    executor = new RecordingExecutor();
    home = base.resolve("home");
    output = new ByteArrayOutputStream();
    paths = HandlerPaths.resolve(_ -> null, home);
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

  private Restart restart(boolean macOS) {
    return new Restart(paths, home, macOS, executor, new PrintStream(output, true));
  }
}

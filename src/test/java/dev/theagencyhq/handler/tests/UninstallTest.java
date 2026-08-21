/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.cli.Uninstall;
import dev.theagencyhq.handler.config.HandlerPaths;

import static org.testng.Assert.*;

public class UninstallTest extends BaseTest {
  private RecordingExecutor executor;
  private Path home;
  private ByteArrayOutputStream output;
  private HandlerPaths paths;

  @Test
  public void aFailingStepIsReportedAndTheRestStillRun() throws IOException {
    installMacOS();
    Uninstall uninstall = new Uninstall(paths, home, true, _ -> {
      throw new IOException("launchctl is missing");
    }, InputStream.nullInputStream(), new PrintStream(output, true));

    assertEquals(uninstall.run(true), 1);
    assertTrue(output.toString().contains("launchctl is missing"), "Output was: " + output);
    assertFalse(Files.exists(home.resolve("Applications/The Agency Handler.app")), "Deletion is best-effort");
    assertFalse(Files.exists(paths.tokensFile()), "Deletion is best-effort");
  }

  @Test
  public void aNonZeroBootoutExitOnlyMeansTheAgentWasNotLoaded() throws IOException {
    installMacOS();
    executor.exitCode = 3;

    assertEquals(uninstall(true, InputStream.nullInputStream()).run(true), 1,
                 "A failing [id -u] is a reported failure");

    output.reset();
    installMacOS();
    executor = new RecordingExecutor() {
      @Override
      public Uninstall.Execution execute(String... command) {
        Uninstall.Execution execution = super.execute(command);
        return command[0].equals("launchctl") ? new Uninstall.Execution(3, "") : execution;
      }
    };

    assertEquals(uninstall(true, InputStream.nullInputStream()).run(true), 0, "Output was: " + output);
  }

  @Test
  public void aNonZeroSystemctlExitOnlyMeansTheServiceWasNotEnabled() throws IOException {
    installLinux();
    executor.exitCode = 1;

    assertEquals(uninstall(false, InputStream.nullInputStream()).run(true), 0, "Output was: " + output);
  }

  @Test
  public void answeringNoLeavesEverythingInPlace() throws IOException {
    installMacOS();

    assertEquals(uninstall(true, new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8))).run(false), 0);

    assertTrue(output.toString().contains("cancelled"), "Output was: " + output);
    assertTrue(Files.exists(home.resolve("Applications/The Agency Handler.app")));
    assertTrue(Files.exists(paths.tokensFile()));
    assertTrue(executor.commands.isEmpty(), "Nothing may run before the confirmation. Ran: " + executor.commands);
  }

  @Test
  public void answeringYesProceeds() throws IOException {
    installMacOS();

    assertEquals(uninstall(true, new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8))).run(false), 0);

    assertFalse(Files.exists(home.resolve("Applications/The Agency Handler.app")));
  }

  @Test
  public void everyCommandRunsBeforeTheBundledRuntimeIsDeletedOnLinux() throws IOException {
    // The uninstall runs on the payload's own JVM, and deleting the payload takes the runtime's spawn helper with
    // it — a command spawned after that cannot start at all
    installLinux();
    executor = new RecordingExecutor() {
      @Override
      public Uninstall.Execution execute(String... command) {
        String joined = String.join(" ", command);
        assertTrue(Files.exists(home.resolve(".local/lib/the-agency-hq/handler")),
                   "[" + joined + "] ran after the payload was deleted");
        if (joined.equals("systemctl --user daemon-reload")) {
          assertFalse(Files.exists(home.resolve(".config/systemd/user/the-agency-hq-handler.service")),
                      "The reload must see the unit file already gone");
        }
        return super.execute(command);
      }
    };

    assertEquals(uninstall(false, InputStream.nullInputStream()).run(true), 0, "Output was: " + output);
    assertTrue(executor.commands.contains("systemctl --user daemon-reload"), "Ran: " + executor.commands);
  }

  @Test
  public void everyCommandRunsBeforeTheBundledRuntimeIsDeletedOnMacOS() throws IOException {
    // The uninstall runs on the bundle's own JVM, and deleting the bundle takes the runtime's spawn helper with
    // it — a command spawned after that cannot start at all
    installMacOS();
    executor = new RecordingExecutor() {
      @Override
      public Uninstall.Execution execute(String... command) {
        assertTrue(Files.exists(home.resolve("Applications/The Agency Handler.app")),
                   "[" + String.join(" ", command) + "] ran after the app bundle was deleted");
        return super.execute(command);
      }
    };

    assertEquals(uninstall(true, InputStream.nullInputStream()).run(true), 0, "Output was: " + output);
    assertTrue(executor.commands.contains("pkgutil --volume " + home + " --forget dev.theagencyhq.handler"),
               "Ran: " + executor.commands);
  }

  @Test
  public void linuxUninstallHonorsTheXDGBaseDirectories() throws IOException {
    // An install made under custom XDG bases must be removed from those bases, not from the spec defaults
    paths = HandlerPaths.resolve(Map.of("XDG_CONFIG_HOME", base.resolve("xdg/config").toString(),
                                        "XDG_DATA_HOME", base.resolve("xdg/data").toString(),
                                        "XDG_STATE_HOME", base.resolve("xdg/state").toString())::get, home);
    installLinux();

    assertEquals(uninstall(false, InputStream.nullInputStream()).run(true), 0, "Output was: " + output);

    assertFalse(Files.exists(base.resolve("xdg/config/systemd/user/the-agency-hq-handler.service")));
    assertFalse(Files.exists(base.resolve("xdg/config/autostart/the-agency-hq-handler.desktop")));
    assertFalse(Files.exists(base.resolve("xdg/data/applications/the-agency-hq-handler.desktop")));
    assertFalse(Files.exists(base.resolve("xdg/data/icons/hicolor/256x256/apps/the-agency-hq-handler.png")));
    assertFalse(Files.exists(home.resolve(".local/lib/the-agency-hq")), "The payload path has no XDG variable");
    assertFalse(Files.exists(home.resolve(".local/bin/handler"), LinkOption.NOFOLLOW_LINKS));
    assertFalse(Files.exists(home.resolve(".config")), "Nothing may be touched under the default bases");
  }

  @Test
  public void linuxUninstallStopsTheServiceAndRemovesTheHandler() throws IOException {
    installLinux();

    assertEquals(uninstall(false, InputStream.nullInputStream()).run(true), 0, "Output was: " + output);

    assertTrue(executor.commands.contains("systemctl --user disable --now the-agency-hq-handler"),
               "Ran: " + executor.commands);
    assertTrue(executor.commands.contains("pkill -x handler-tray"), "Ran: " + executor.commands);
    assertTrue(executor.commands.contains("systemctl --user daemon-reload"), "Ran: " + executor.commands);

    assertFalse(Files.exists(home.resolve(".config/systemd/user/the-agency-hq-handler.service")));
    assertFalse(Files.exists(home.resolve(".config/autostart/the-agency-hq-handler.desktop")));
    assertFalse(Files.exists(home.resolve(".local/share/applications/the-agency-hq-handler.desktop")));
    assertFalse(Files.exists(home.resolve(".local/share/icons/hicolor/256x256/apps/the-agency-hq-handler.png")));
    assertFalse(Files.exists(home.resolve(".local/lib/the-agency-hq")), "The emptied payload directory is removed");
    assertFalse(Files.exists(home.resolve(".local/bin/handler"), LinkOption.NOFOLLOW_LINKS));
    assertFalse(Files.exists(paths.configFile()));
    assertFalse(Files.exists(paths.tokensFile()));
    assertFalse(Files.exists(paths.storeRoot().getParent()), "The emptied data directory is removed");
    assertFalse(Files.exists(paths.logFile().getParent()), "The emptied state directory is removed");

    // The configuration directory is shared with the other Agency HQ tools and must survive with their files
    assertTrue(Files.exists(paths.configFile().getParent()));
    assertEquals(Files.readString(paths.configFile().getParent().resolve("webapp.json")), "{\"theirs\":true}");
  }

  @Test
  public void runningTwiceIsNotAnError() throws IOException {
    installMacOS();

    assertEquals(uninstall(true, InputStream.nullInputStream()).run(true), 0);
    assertEquals(uninstall(true, InputStream.nullInputStream()).run(true), 0, "Output was: " + output);
  }

  @BeforeMethod
  public void setUp() {
    executor = new RecordingExecutor();
    home = base.resolve("home");
    output = new ByteArrayOutputStream();
    paths = HandlerPaths.resolve(_ -> null, home);
  }

  @Test
  public void uninstallRemovesTheHandlerAndLeavesTheSharedConfigurationDirectory() throws IOException {
    installMacOS();

    assertEquals(uninstall(true, InputStream.nullInputStream()).run(true), 0, "Output was: " + output);

    // The launch agents were booted out under the uid that [id -u] answered, and the receipt was forgotten
    assertTrue(executor.commands.contains("launchctl bootout gui/501/dev.theagencyhq.handler.daemon"),
               "Ran: " + executor.commands);
    assertTrue(executor.commands.contains("launchctl bootout gui/501/dev.theagencyhq.handler.tray"),
               "Ran: " + executor.commands);
    assertTrue(executor.commands.contains("pkgutil --volume " + home + " --forget dev.theagencyhq.handler"),
               "Ran: " + executor.commands);

    assertFalse(Files.exists(home.resolve("Library/LaunchAgents/dev.theagencyhq.handler.daemon.plist")));
    assertFalse(Files.exists(home.resolve("Library/LaunchAgents/dev.theagencyhq.handler.tray.plist")));
    assertFalse(Files.exists(home.resolve("Applications/The Agency Handler.app")));
    assertFalse(Files.exists(home.resolve(".local/bin/handler"), LinkOption.NOFOLLOW_LINKS));
    assertFalse(Files.exists(paths.configFile()));
    assertFalse(Files.exists(paths.tokensFile()));
    assertFalse(Files.exists(paths.storeRoot().getParent()), "The emptied data directory is removed");
    assertFalse(Files.exists(paths.logFile().getParent()), "The emptied state directory is removed");

    // The configuration directory is shared with the other Agency HQ tools and must survive with their files
    assertTrue(Files.exists(paths.configFile().getParent()));
    assertEquals(Files.readString(paths.configFile().getParent().resolve("webapp.json")), "{\"theirs\":true}");
  }

  /**
   * Lays out everything install.sh places, plus the files a running Handler writes.
   */
  private void installLinux() throws IOException {
    Path payload = home.resolve(".local/lib/the-agency-hq/handler");
    Files.createDirectories(payload.resolve("bin"));
    Files.writeString(payload.resolve("bin/handler"), "#!/bin/bash\n");
    Files.createDirectories(payload.resolve("lib"));
    Files.writeString(payload.resolve("lib/handler.jar"), "jar");
    Files.writeString(payload.resolve("handler-tray"), "binary");

    // install.sh writes these under the XDG bases, so the layout follows whatever bases [paths] resolved
    Files.createDirectories(paths.configBase().resolve("systemd/user"));
    Files.writeString(paths.configBase().resolve("systemd/user/the-agency-hq-handler.service"), "[Unit]");
    Files.createDirectories(paths.configBase().resolve("autostart"));
    Files.writeString(paths.configBase().resolve("autostart/the-agency-hq-handler.desktop"), "[Desktop Entry]");
    Files.createDirectories(paths.dataBase().resolve("applications"));
    Files.writeString(paths.dataBase().resolve("applications/the-agency-hq-handler.desktop"), "[Desktop Entry]");
    Files.createDirectories(paths.dataBase().resolve("icons/hicolor/256x256/apps"));
    Files.writeString(paths.dataBase().resolve("icons/hicolor/256x256/apps/the-agency-hq-handler.png"), "png");

    Files.createDirectories(home.resolve(".local/bin"));
    Files.createSymbolicLink(home.resolve(".local/bin/handler"), payload.resolve("bin/handler"));

    userFiles();
  }

  /**
   * Lays out everything the pkg postinstall places, plus the files a running Handler writes.
   */
  private void installMacOS() throws IOException {
    Path app = home.resolve("Applications/The Agency Handler.app");
    Files.createDirectories(app.resolve("Contents/Resources/daemon/bin"));
    Files.writeString(app.resolve("Contents/Resources/daemon/bin/handler"), "#!/bin/bash\n");
    Files.createDirectories(app.resolve("Contents/MacOS"));
    Files.writeString(app.resolve("Contents/MacOS/handler-tray"), "binary");

    Path agents = Files.createDirectories(home.resolve("Library/LaunchAgents"));
    Files.writeString(agents.resolve("dev.theagencyhq.handler.daemon.plist"), "<plist/>");
    Files.writeString(agents.resolve("dev.theagencyhq.handler.tray.plist"), "<plist/>");

    Files.createDirectories(home.resolve(".local/bin"));
    Files.createSymbolicLink(home.resolve(".local/bin/handler"), app.resolve("Contents/Resources/daemon/bin/handler"));

    userFiles();
  }

  private Uninstall uninstall(boolean macOS, InputStream in) {
    return new Uninstall(paths, home, macOS, executor, in, new PrintStream(output, true));
  }

  /**
   * The files the Handler writes while running, plus another tool's file in the shared configuration directory.
   * FileHandler never writes a bare [handler.log] — with a count above one it writes rotated generations and drops
   * [.lck] files next to them, which is what the uninstall has to match.
   */
  private void userFiles() throws IOException {
    Files.createDirectories(paths.configFile().getParent());
    Files.writeString(paths.configFile(), "{}");
    Files.writeString(paths.tokensFile(), "{}");
    Files.writeString(paths.configFile().getParent().resolve("webapp.json"), "{\"theirs\":true}");

    Files.createDirectories(paths.storeRoot().resolve("42"));
    Files.writeString(paths.storeRoot().resolve("42/1.json"), "{}");
    Files.createDirectories(paths.logFile().getParent());
    Files.writeString(paths.logFile().getParent().resolve("handler.log.0"), "log");
    Files.writeString(paths.logFile().getParent().resolve("handler.log.1"), "log");
    Files.writeString(paths.logFile().getParent().resolve("handler.log.0.lck"), "");
    Files.writeString(paths.socketFile(), "");
  }

  /**
   * Answers every command with success, [id -u] with uid 501, and records everything that ran.
   */
  private static class RecordingExecutor implements Uninstall.Executor {
    List<String> commands = new ArrayList<>();
    int exitCode;

    @Override
    public Uninstall.Execution execute(String... command) {
      String joined = String.join(" ", command);
      commands.add(joined);
      return new Uninstall.Execution(exitCode, joined.equals("id -u") ? "501\n" : "");
    }
  }
}

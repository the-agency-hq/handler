/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.cli.Version;

import static org.testng.Assert.*;

public class HandlerCLITest extends BaseTest {
  private FakeAgency agency;
  private Handler handler;
  private ByteArrayOutputStream output;

  @Test
  public void aMalformedAuthURLFailsCLIStartup() throws IOException {
    // "localhost:9015" parses with scheme [localhost] and no host, which is the typo the README's instruction invites.
    // The configuration file is required to be perfect, therefore we fail the CLI run
    expectThrows(AuthenticationException.class, () -> assertEquals(cli("localhost:9015").run("status"), 0));
  }

  @Test(timeOut = 15_000)
  public void daemonStartsWhenTheIssuerCannotBeReached() throws Exception {
    // The credential may well be fine; the Handler simply cannot tell yet, so the advice must be about the network
    tokenStore().store(new Tokens("access", "refresh"));

    int exit = daemon();

    String printed = output.toString();
    assertEquals(exit, 0);
    assertTrue(printed.contains("could not reach"), "Output was: " + printed);
    assertTrue(printed.contains("network"), "Output was: " + printed);
    assertFalse(printed.contains("handler login"), "Wrong advice when the network is down: " + printed);
  }

  @Test(timeOut = 15_000)
  public void daemonStartsWithoutCredentialsInsteadOfCrashLooping() throws Exception {
    // Exiting here would only crash-loop under launchd and systemd; the daemon runs, reports LOGGED_OUT, and the
    // receive loop adopts the tokens a later [handler login] writes
    int exit = daemon();

    String printed = output.toString();
    assertEquals(exit, 0);
    assertTrue(printed.contains("not logged in"), "Output was: " + printed);
    assertTrue(printed.contains("handler login"), "Output was: " + printed);
  }

  @Test
  public void helpAndVersionExitZero() throws IOException {
    assertEquals(cli().run("help"), 0);
    assertEquals(cli().run("--version"), 0);
    assertTrue(output.toString().contains("handler"), "Output was: " + output);
  }

  @Test
  public void helpNamesEverySubcommand() throws IOException {
    assertEquals(cli().run("help"), 0);
    for (String command : List.of("daemon", "start", "stop", "restart", "sync", "status", "init", "init-source", "login",
                                  "logout", "uninstall", "help", "--version")) {
      assertTrue(output.toString().contains(command), "Missing [" + command + "]. Output was: " + output);
    }
  }

  @Test
  public void initIsDispatchedAndReportsAnAgencyFailure() throws IOException {
    // Nothing scripted, so the FakeAgency answers 500 — this only proves the subcommand reaches Init
    assertEquals(cli().run("init"), 1);
    assertTrue(output.toString().contains("status [500]"), "Output was: " + output);
  }

  @Test
  public void initSourceIsDispatchedAndScaffoldsTheCurrentDirectory() throws IOException {
    assertEquals(cli().run("init-source"), 0);
    assertTrue(Files.exists(base.resolve(InitSource.SETTINGS_FILENAME)), "Output was: " + output);
  }

  @Test
  public void logoutClearsTheTokenFileAndIsIdempotent() throws IOException {
    tokenStore().store(new Tokens("access", "refresh"));

    assertEquals(cli().run("logout"), 0);
    assertFalse(Files.exists(tokensFile()));
    assertTrue(output.toString().contains("Logged out"), "Output was: " + output);

    output.reset();
    assertEquals(cli().run("logout"), 0, "Logging out twice is not an error");
    assertTrue(output.toString().contains("Not logged in"), "Output was: " + output);
  }

  @BeforeMethod
  public void setUp() {
    output = new ByteArrayOutputStream();
    agency = new FakeAgency();
    agency.start();
  }

  @Test
  public void startStopAndRestartAreDispatched() throws IOException {
    // The test home holds no launchd plist, so reaching the not-installed message proves the dispatch
    for (String command : List.of("start", "stop", "restart")) {
      output.reset();
      assertEquals(cli().run(command), 1);
      assertTrue(output.toString().contains("not installed"), "Output was: " + output);
    }
  }

  @Test
  public void statusNamesEveryLocationFromTheStateFileAndWritesNothing() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42", List.of("code", "docs"), List.of("claude", "agents"));
    Path orphan = location("orphan", "999");
    Path gone = location("gone", "42");
    assertEquals(cli().run("sync"), 0);

    // Everything after the sync is what the next distribute will act on, and the state file does not know about it
    store.store(brief("42", 2, file(".claude/a.md", "beta")));
    Files.delete(gone.resolve("agent-location.json"));
    Path unscanned = location("unscanned", "42");

    output.reset();
    assertEquals(cli().run("status"), 0);

    String printed = output.toString();
    assertTrue(printed.contains("Locations (last daemon run "), "Output was: " + printed);
    assertTrue(printed.contains("  " + location + "\n    Mission types: code, docs\n    Agent types:   claude, agents\n"
                                + "    Status:        Pending new version\n"), "Output was: " + printed);
    assertTrue(printed.contains("  " + orphan + "\n    Mission types: all\n    Agent types:   all\n"
                                + "    Status:        No Brief\n"), "Output was: " + printed);
    assertTrue(printed.contains("  " + gone + "\n    Mission types: all\n    Agent types:   all\n"
                                + "    Status:        Removed\n"), "Output was: " + printed);
    assertFalse(printed.contains(unscanned.toString()), "Status never scans. Output was: " + printed);

    // A pure read - status must not bootstrap a manifest
    assertFalse(Files.exists(orphan.resolve(".handler-manifest")));
    assertFalse(Files.exists(unscanned.resolve(".handler-manifest")));
  }

  @Test
  public void statusReportsAnUnreadableTokenFileRatherThanFailing() throws IOException {
    // The daemon already treats a mangled tokens.json as non-fatal; the command run to find it must not be stricter
    Files.createDirectories(tokensFile().getParent());
    Files.writeString(tokensFile(), "{not json at all");

    assertEquals(cli().run("status"), 0);
    assertTrue(output.toString().contains("Access token:    unreadable"), "Output was: " + output);
  }

  @Test
  public void statusReportsAnUnreadableStateFileRatherThanFailing() throws IOException {
    Files.createDirectories(base.resolve("state.json").getParent());
    Files.writeString(base.resolve("state.json"), "{not json at all");

    assertEquals(cli().run("status"), 0);
    String printed = output.toString();
    assertTrue(printed.contains("Locations\n  Unknown. The state file could not be read ("), "Output was: " + printed);
    assertTrue(printed.contains("This status output will update the next time the daemon runs."),
               "Output was: " + printed);
  }

  @Test
  public void statusReportsBothUpToDateAndConflictStates() throws IOException {
    // Store "43" only AFTER the sync below: ReceiveTask treats a briefing response's organizationIds as
    // authoritative and revokes (then purges, with no Location yet to defer it) anything already stored that the
    // response omits - storing "43" first would have it revoked-and-purged out from under this test by "sync".
    // Its Location has to exist before the sync, though, or the state file will not list it.
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    Path unchanged = location("applied", "42");
    Path conflicted = location("conflicted", "43");
    assertEquals(cli().run("sync"), 0, "The conflicted Location has no Brief yet, so it is skipped cleanly");

    store.store(brief("43", 1, file(".claude/a.md", "alpha")));
    Files.createDirectories(conflicted.resolve(".claude"));
    Files.writeString(conflicted.resolve(".claude/a.md"), "unmanaged");

    output.reset();
    assertEquals(cli().run("status"), 0);

    String printed = output.toString();
    assertTrue(printed.contains("  " + unchanged + "\n    Mission types: all\n    Agent types:   all\n"
                                + "    Status:        Up-to-date\n"), "Output was: " + printed);
    assertTrue(printed.contains("  " + conflicted + "\n    Mission types: all\n    Agent types:   all\n"
                                + "    Status:        Skipped due to conflicts\n"), "Output was: " + printed);
  }

  @Test
  public void statusWithoutAStateFileReportsUnknownLocations() throws IOException {
    Path location = location("app", "42");

    assertEquals(cli().run("status"), 0);

    String printed = output.toString();
    assertTrue(printed.contains("Config file:     " + base.resolve("handler.json")), "Output was: " + printed);
    assertTrue(printed.contains("State file:      " + base.resolve("state.json")), "Output was: " + printed);
    assertTrue(printed.contains("Organizations\n  (none)\n"), "Output was: " + printed);
    assertTrue(printed.contains("Locations\n  Unknown. This status output will update the next time the daemon runs.\n"),
               "Output was: " + printed);
    assertFalse(printed.contains(location.toString()), "Status never scans. Output was: " + printed);
  }

  @Test
  public void statusReportsTokenPresenceFromTheTokenStoreAndNeverPrintsIt() throws IOException {
    assertEquals(cli().run("status"), 0);
    assertTrue(output.toString().contains("Access token:    absent"), "Output was: " + output);

    output.reset();
    tokenStore().store(new Tokens("super-secret-token", "refresh"));

    assertEquals(cli().run("status"), 0);
    String printed = output.toString();
    assertTrue(printed.contains("Access token:    present"), "Output was: " + printed);
    assertFalse(printed.contains("super-secret-token"), "The token must never be printed. Output was: " + printed);
  }

  @Test
  public void syncExitsOneWhenALocationConflicts() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "unmanaged");

    assertEquals(cli().run("sync"), 1);
  }

  @Test
  public void syncExitsZeroAndForceAdoptsAConflict() throws IOException {
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    Files.createDirectories(location.resolve(".claude"));
    Files.writeString(location.resolve(".claude/a.md"), "unmanaged");

    assertEquals(cli().run("sync"), 1);
    assertEquals(cli().run("sync", "--force"), 0);
    assertEquals(Files.readString(location.resolve(".claude/a.md")), "alpha");
  }

  @AfterMethod
  public void tearDown() {
    agency.close();
  }

  @Test
  public void uninstallIsDispatchedAndAsksForConfirmation() throws IOException {
    // The wired input never answers, so reaching the cancellation message proves the subcommand reaches Uninstall
    assertEquals(cli().run("uninstall"), 0);
    assertTrue(output.toString().contains("cancelled"), "Output was: " + output);
  }

  @Test
  public void unknownSubcommandExitsOne() throws IOException {
    assertEquals(cli().run("frobnicate"), 1);
  }

  /**
   * Points {@code authURL} at a port nothing is listening on. {@code status} introspects the stored token against the
   * issuer, so without this every test that runs it would reach for the real production IdP over the network — slow
   * when it resolves and flaky when it does not. A refused connection is immediate and deterministic.
   */
  private HandlerCLI cli() throws IOException {
    return cli("http://127.0.0.1:" + closedPort());
  }

  private HandlerCLI cli(String authURL) throws IOException {
    HandlerConfig config = new HandlerConfig(locations().toString(), null, agency.url(), authURL, 3600, 3600);
    HandlerPaths paths = new HandlerPaths(base.resolve("handler.json"), tokensFile(), storeRoot(),
                                          base.resolve("handler.log"));
    LocationScanner scanner = new LocationScanner(config);
    BriefPlanner planner = new BriefPlanner();
    LocationApplier applier = new LocationApplier();
    StateStore stateStore = new StateStore(paths.stateFile());
    DistributeThread distributeThread = new DistributeThread(config, store, scanner, planner, applier, stateStore);
    AgencyClient agencyClient = new AgencyClient(config.theAgencyURL(), new StubTokenSupplier("test-token"));
    handler = new Handler(config, agencyClient, store, distributeThread);
    TokenStore tokenStore = tokenStore();
    // Mirrors Main, which resolves rather than constructs so a bad authURL cannot keep the CLI from running
    AuthConfiguration authConfiguration = new AuthConfiguration(config.authURL());
    OAuthClient oauthClient = new OAuthClient(authConfiguration);
    Credentials credentials = new Credentials(tokenStore, oauthClient, new AccessTokens(authConfiguration));
    PrintStream printStream = new PrintStream(output, true);
    OrganizationSelector selector = new OrganizationSelector(InputStream.nullInputStream(), printStream,
                                                             new StubTerminal());

    Daemon daemon = new Daemon(handler, credentials, printStream);
    // The test home holds neither a launchd plist nor a systemd unit, so a dispatched service command can only
    // report that nothing is installed
    ProcessCommand.Executor executor = _ -> new ProcessCommand.ExecutionResult(0, "");
    Start start = new Start(paths, base, true, executor, printStream);
    Stop stop = new Stop(paths, base, true, executor, printStream);
    Restart restart = new Restart(paths, base, true, executor, printStream);
    Sync sync = new Sync(handler);
    Status status = new Status(paths, config, store, stateStore, planner, applier, tokenStore, credentials,
                               printStream);
    Init init = new Init(agencyClient, selector, base, InputStream.nullInputStream(), printStream);
    InitSource initSource = new InitSource(base, printStream);
    Login login = new Login(authConfiguration, oauthClient, tokenStore, (url, out) -> { }, printStream);
    Logout logout = new Logout(tokenStore, printStream);
    // The null input stream never answers the confirmation, so a dispatched uninstall can only cancel itself
    Uninstall uninstall = new Uninstall(paths, base, true, executor, InputStream.nullInputStream(), printStream);

    return new HandlerCLI(daemon, start, stop, restart, sync, status, init, initSource, login, logout,
                          uninstall, new Help(printStream), new Version(printStream), printStream);
  }

  /**
   * @return A port that was just released, so connecting to it is refused rather than hanging.
   */
  private int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  /**
   * Runs [handler daemon] on its own thread, waits for the preflight verdict to print, proves the daemon stayed up
   * anyway, then shuts it down.
   *
   * @return The exit code.
   */
  private int daemon() throws Exception {
    HandlerCLI cli = cli();
    AtomicInteger exit = new AtomicInteger(-1);
    Thread caller = new Thread(() -> exit.set(cli.run("daemon")), "daemon-caller");
    caller.start();

    long deadline = System.currentTimeMillis() + 10_000;
    while (output.size() == 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(25);
    }

    assertTrue(caller.isAlive(), "The daemon must keep running despite the failed preflight. Output was: " + output);

    handler.shutdown();
    caller.join(10_000);
    assertFalse(caller.isAlive(), "shutdown() must release the daemon");
    return exit.get();
  }

  private Path tokensFile() {
    return base.resolve("config/tokens.json");
  }

  private TokenStore tokenStore() {
    return new TokenStore(tokensFile());
  }
}

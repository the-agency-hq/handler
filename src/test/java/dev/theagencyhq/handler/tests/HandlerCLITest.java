/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.Handler;
import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.auth.*;
import dev.theagencyhq.handler.cli.*;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.config.HandlerPaths;
import dev.theagencyhq.handler.location.LocationScanner;

import static org.testng.Assert.*;

public class HandlerCLITest extends BaseTest {
  private FakeAgency agency;
  private ByteArrayOutputStream output;

  @Test
  public void aMalformedAuthURLFailsCLIStartup() throws IOException {
    // "localhost:9015" parses with scheme [localhost] and no host, which is the typo the README's instruction invites.
    // The configuration file is required to be perfect, therefore we fail the CLI run
    expectThrows(AuthenticationException.class, () -> assertEquals(cli("localhost:9015").run("status"), 0));
  }

  @Test
  public void daemonRefusesToStartWhenTheIssuerCannotBeReached() throws IOException {
    // The credential may well be fine; the Handler simply cannot tell yet, so the advice must be about the network
    tokenStore().store(new Tokens("access", "refresh"));

    assertEquals(cli().run("daemon"), 1);

    String printed = output.toString();
    assertTrue(printed.contains("could not reach"), "Output was: " + printed);
    assertTrue(printed.contains("network"), "Output was: " + printed);
    assertFalse(printed.contains("handler login"), "Wrong advice when the network is down: " + printed);
  }

  @Test
  public void daemonRefusesToStartWithoutCredentials() throws IOException {
    // Starting anyway would look healthy to launchd while receiving nothing, and the developer would find out hours
    // later from an empty Location instead of now, from a message
    assertEquals(cli().run("daemon"), 1);

    String printed = output.toString();
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
  public void helpNamesInitLoginAndLogout() throws IOException {
    assertEquals(cli().run("help"), 0);
    assertTrue(output.toString().contains("init"), "Output was: " + output);
    assertTrue(output.toString().contains("login"), "Output was: " + output);
    assertTrue(output.toString().contains("logout"), "Output was: " + output);
  }

  @Test
  public void initIsDispatchedAndReportsAnAgencyFailure() throws IOException {
    // Nothing scripted, so the FakeAgency answers 500 — this only proves the subcommand reaches Init
    assertEquals(cli().run("init"), 1);
    assertTrue(output.toString().contains("status [500]"), "Output was: " + output);
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
  public void statusNamesEveryLocationAndItsStateAndWritesNothing() throws IOException {
    store.store(brief("42", 1, file(".claude/a.md", "alpha")));
    Path location = location("app", "42");
    Path orphan = location("orphan", "999");

    assertEquals(cli().run("status"), 0);

    String printed = output.toString();
    assertTrue(printed.contains(location.toString()), "Output was: " + printed);
    assertTrue(printed.contains("changed"), "Output was: " + printed);
    assertTrue(printed.contains(orphan.toString()), "Output was: " + printed);
    assertTrue(printed.contains("no brief"), "Output was: " + printed);

    // A pure read - status must not bootstrap a manifest
    assertFalse(Files.exists(location.resolve(".handler-manifest")));
    assertFalse(Files.exists(orphan.resolve(".handler-manifest")));
  }

  @Test
  public void statusReportsAnUnreadableTokenFileRatherThanFailing() throws IOException {
    // The daemon already treats a mangled tokens.json as non-fatal; the command run to find it must not be stricter
    Files.createDirectories(tokensFile().getParent());
    Files.writeString(tokensFile(), "{not json at all");

    assertEquals(cli().run("status"), 0);
    assertTrue(output.toString().contains("accessToken  unreadable"), "Output was: " + output);
  }

  @Test
  public void statusReportsBothUnchangedAndConflictStates() throws IOException {
    // Store "43" only AFTER the sync below: ReceiveTask treats a briefing response's organizationIds as
    // authoritative and revokes (then purges, with no Location yet to defer it) anything already stored that the
    // response omits - storing "43" first would have it revoked-and-purged out from under this test by "sync".
    agency.script(200, response("42", 1, file(".claude/a.md", "alpha")));
    Path unchanged = location("applied", "42");
    assertEquals(cli().run("sync"), 0, "Only the clean Location exists at this point");

    store.store(brief("43", 1, file(".claude/a.md", "alpha")));
    Path conflicted = location("conflicted", "43");
    Files.createDirectories(conflicted.resolve(".claude"));
    Files.writeString(conflicted.resolve(".claude/a.md"), "unmanaged");

    assertEquals(cli().run("status"), 0);

    String printed = output.toString();
    assertTrue(printed.contains("  " + unchanged + "  unchanged"), "Output was: " + printed);
    assertTrue(printed.contains("  " + conflicted + "  conflict"), "Output was: " + printed);
  }

  @Test
  public void statusReportsTokenPresenceFromTheTokenStoreAndNeverPrintsIt() throws IOException {
    assertEquals(cli().run("status"), 0);
    assertTrue(output.toString().contains("accessToken  absent"), "Output was: " + output);

    output.reset();
    tokenStore().store(new Tokens("super-secret-token", "refresh"));

    assertEquals(cli().run("status"), 0);
    String printed = output.toString();
    assertTrue(printed.contains("accessToken  present"), "Output was: " + printed);
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
  public void unknownSubcommandExitsOne() throws IOException {
    assertEquals(cli().run("frobnicate"), 1);
  }

  /**
   * Points {@code authURL} at a port nothing is listening on. {@code status} introspects the stored token against the
   * issuer, so without this every test that runs it would reach for the real production IdP over the network — slow
   * when it resolves and flaky when it does not. A refused connection is immediate and deterministic.
   */
  private HandlerCLI cli() throws IOException {
    return cli("http://localhost:" + closedPort());
  }

  private HandlerCLI cli(String authURL) throws IOException {
    HandlerConfig config = new HandlerConfig(locations().toString(), null, agency.url(), authURL, 3600, 3600);
    HandlerPaths paths = new HandlerPaths(base.resolve("handler.json"), tokensFile(), storeRoot(),
                                          base.resolve("handler.log"));
    LocationScanner scanner = new LocationScanner(config);
    BriefPlanner planner = new BriefPlanner();
    LocationApplier applier = new LocationApplier();
    DistributeThread distributeThread = new DistributeThread(config, store, scanner, planner, applier);
    AgencyClient agencyClient = new AgencyClient(config.theAgencyURL(), new StubTokenSupplier("test-token"));
    Handler handler = new Handler(config, agencyClient, store, distributeThread);
    TokenStore tokenStore = tokenStore();
    // Mirrors Main, which resolves rather than constructs so a bad authURL cannot keep the CLI from running
    AuthConfiguration authConfiguration = new AuthConfiguration(config.authURL());
    OAuthClient oauthClient = new OAuthClient(authConfiguration);
    Login login = new Login(authConfiguration, oauthClient, tokenStore, (url, out) -> { });
    Credentials credentials = new Credentials(tokenStore, oauthClient, new AccessTokens(authConfiguration));
    PrintStream printStream = new PrintStream(output, true);
    OrganizationSelector selector = new OrganizationSelector(InputStream.nullInputStream(), printStream,
                                                             new StubTerminal());
    Init init = new Init(agencyClient, selector, base, InputStream.nullInputStream(), printStream);

    return new HandlerCLI(paths, config, store, scanner, planner, applier, handler, init, login, tokenStore,
                          credentials, printStream);
  }

  /**
   * @return A port that was just released, so connecting to it is refused rather than hanging.
   */
  private int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private Path tokensFile() {
    return base.resolve("config/tokens.json");
  }

  private TokenStore tokenStore() {
    return new TokenStore(tokensFile());
  }
}

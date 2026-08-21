/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.agency.BriefingResult;
import dev.theagencyhq.handler.tray.TrayFeed;
import dev.theagencyhq.handler.tray.TrayState;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Exercises the Unix domain socket feed the external tray reads: the snapshot on connect, the status line per cycle,
 * the notification lines, and the socket file lifecycle.
 *
 * <p>These tests use their own short temp directory rather than {@link BaseTest#base} because a Unix domain socket
 * path is limited to roughly 104 bytes on macOS, and the build directory's scratch paths exceed it.
 *
 * @author Brian Pontarelli
 */
public class TrayFeedTest extends BaseTest {
  @Test(timeOut = 10_000)
  public void aPendingLoggedOutAnnouncementWaitsForTheFirstClient() throws Exception {
    // A daemon that starts logged out has no tray connected yet; the announcement must not be lost, and a client
    // that connects after it was delivered must not hear it again
    Path socket = Files.createTempDirectory("tray-feed").resolve("handler.sock");
    TrayFeed feed = new TrayFeed(socket);
    feed.start(TrayState.LOGGED_OUT);
    try (SocketChannel first = SocketChannel.open(StandardProtocolFamily.UNIX);
         SocketChannel second = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      first.connect(UnixDomainSocketAddress.of(socket));
      BufferedReader firstReader = reader(first);
      assertTrue(firstReader.readLine().contains("\"state\":\"LOGGED_OUT\""));
      assertTrue(firstReader.readLine().contains("not logged in"), "The pending announcement follows the snapshot");

      second.connect(UnixDomainSocketAddress.of(socket));
      BufferedReader secondReader = reader(second);
      assertTrue(secondReader.readLine().contains("\"state\":\"LOGGED_OUT\""));

      // The next line each client sees is the recovery status — the second client never got a duplicate announcement
      feed.received(new BriefingResult.NotModified());
      assertTrue(firstReader.readLine().contains("\"state\":\"HEALTHY\""));
      assertTrue(secondReader.readLine().contains("\"state\":\"HEALTHY\""));
    } finally {
      feed.stop();
    }
  }

  @Test(timeOut = 10_000)
  public void aStaleSocketFileIsReclaimed() throws Exception {
    Path socket = Files.createTempDirectory("tray-feed").resolve("handler.sock");
    Files.createFile(socket);

    TrayFeed feed = new TrayFeed(socket);
    feed.start(TrayState.HEALTHY);
    try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      client.connect(UnixDomainSocketAddress.of(socket));
      assertTrue(reader(client).readLine().startsWith("{\"type\":\"status\""));
    } finally {
      feed.stop();
    }

    assertFalse(Files.exists(socket), "stop() must remove the socket file");
  }

  @Test(timeOut = 10_000)
  public void feedServesStatusAndNotifications() throws Exception {
    Path socket = Files.createTempDirectory("tray-feed").resolve("handler.sock");
    TrayFeed feed = new TrayFeed(socket);
    feed.start(TrayState.HEALTHY);
    try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      client.connect(UnixDomainSocketAddress.of(socket));
      BufferedReader reader = reader(client);

      // The snapshot arrives on connect, before any cycle has run
      assertEquals(reader.readLine(),
          "{\"type\":\"status\",\"state\":\"HEALTHY\",\"lastResponse\":null,\"lastRun\":null,\"locations\":null,\"lastChanges\":null}");

      // An authentication failure flips the state without recording a response, and announces the logged-out state
      feed.received(new BriefingResult.Failed("rejected", true));
      String line = reader.readLine();
      assertTrue(line.contains("\"state\":\"LOGGED_OUT\""), line);
      assertTrue(line.contains("\"lastResponse\":null"), line);
      assertFalse(line.contains("\"lastRun\":null"), line);
      assertEquals(reader.readLine(), "{\"type\":\"notification\",\"message\":\"The Handler is not logged in to"
          + " The Agency HQ. Run [handler login] to resume syncing.\"}");

      // New Briefs record a response and announce themselves
      feed.received(new BriefingResult.Updated(List.of("42"), List.of(brief("42", 1))));
      line = reader.readLine();
      assertTrue(line.contains("\"state\":\"HEALTHY\""), line);
      assertFalse(line.contains("\"lastResponse\":null"), line);
      assertEquals(reader.readLine(),
          "{\"type\":\"notification\",\"message\":\"Received 1 updated Brief from The Agency\"}");

      // A distribute cycle reports the scan and announces applied changes with the change set
      feed.distributed(new DistributeThread.Summary(2, 5, 1, 0), 7);
      line = reader.readLine();
      assertTrue(line.contains("\"locations\":7"), line);
      assertTrue(line.contains("\"lastChanges\":{\"applied\":2,\"conflict\":1,\"failed\":0,\"at\":"), line);
      assertEquals(reader.readLine(),
          "{\"type\":\"notification\",\"message\":\"Applied changes to 2 Locations (2 applied, 1 conflict)\"}");
    } finally {
      feed.stop();
    }
  }

  @Test(timeOut = 10_000)
  public void loggedOutIsAnnouncedOncePerEpisodeNotOncePerCycle() throws Exception {
    Path socket = Files.createTempDirectory("tray-feed").resolve("handler.sock");
    TrayFeed feed = new TrayFeed(socket);
    feed.start(TrayState.HEALTHY);
    try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      client.connect(UnixDomainSocketAddress.of(socket));
      BufferedReader reader = reader(client);
      assertTrue(reader.readLine().contains("\"state\":\"HEALTHY\""));

      // Entering LOGGED_OUT announces once
      feed.received(new BriefingResult.Failed("rejected", true));
      assertTrue(reader.readLine().contains("\"state\":\"LOGGED_OUT\""));
      assertTrue(reader.readLine().contains("not logged in"));

      // Staying LOGGED_OUT is silent: the next line after this cycle's status is the recovery status, not a repeat
      feed.received(new BriefingResult.Failed("rejected", true));
      assertTrue(reader.readLine().contains("\"state\":\"LOGGED_OUT\""));
      feed.received(new BriefingResult.NotModified());
      assertTrue(reader.readLine().contains("\"state\":\"HEALTHY\""));

      // A new logged-out episode announces again
      feed.received(new BriefingResult.Failed("rejected", true));
      assertTrue(reader.readLine().contains("\"state\":\"LOGGED_OUT\""));
      assertTrue(reader.readLine().contains("not logged in"));
    } finally {
      feed.stop();
    }
  }

  @Test(timeOut = 10_000)
  public void stopWithoutStartLeavesTheRunningDaemonsSocketAlone() throws Exception {
    // Every CLI process wires a feed and shuts it down on exit; the socket file it never bound belongs to the running
    // daemon, and deleting it would cut the tray off until the daemon restarts
    Path socket = Files.createTempDirectory("tray-feed").resolve("handler.sock");
    Files.createFile(socket);

    new TrayFeed(socket).stop();

    assertTrue(Files.exists(socket), "stop() without start() must not delete the daemon's socket");
  }

  private BufferedReader reader(SocketChannel client) {
    return new BufferedReader(new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
  }
}

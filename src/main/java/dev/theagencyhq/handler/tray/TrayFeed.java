/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tray;

import module java.base;

import dev.theagencyhq.handler.DistributeThread;
import dev.theagencyhq.handler.agency.BriefingResult;

/**
 * Serves the daemon's tray state over a Unix domain socket so the external GoGPU tray process can show it without
 * living inside this JVM. Every method is safe to call anywhere: a feed that cannot bind, a client that disconnects,
 * or a write that fails is logged and swallowed, because a broken status feed must never stop syncing.
 *
 * <p>The protocol is newline-delimited JSON, server to client only. On connect the client immediately receives one
 * status line, then another after every receive or distribute cycle, and a notification line for every event worth
 * announcing:
 *
 * <pre>
 * {"type":"status","state":"HEALTHY","lastResponse":1755550000000,"lastRun":1755550000000,"locations":3,
 *  "lastChanges":{"applied":1,"conflict":0,"failed":0,"at":1755550000000}}
 * {"type":"notification","message":"Received 1 updated Brief from The Agency"}
 * </pre>
 *
 * <p>Timestamps are epoch milliseconds and null before the first cycle, {@code locations} is null until the first
 * scan, and {@code lastChanges} is null until the Handler changes something. Formatting for humans is the client's
 * job, so the daemon never bakes a timezone into the feed.
 *
 * @author Brian Pontarelli
 */
public class TrayFeed {
  private static final System.Logger LOG = System.getLogger(TrayFeed.class.getName());
  private static final String LOGGED_OUT_NOTIFICATION =
      "The Handler is not logged in to The Agency HQ. Run [handler login] to resume syncing.";

  private volatile boolean bound;
  private final Set<SocketChannel> clients = ConcurrentHashMap.newKeySet();
  private volatile Changes lastChanges;
  private volatile Instant lastResponse;
  private volatile Instant lastRun;
  private volatile int locations = -1;
  private boolean loggedOutAnnounced;
  private volatile ServerSocketChannel server;
  private final Path socketFile;
  private volatile TrayState state = TrayState.HEALTHY;

  public TrayFeed(Path socketFile) {
    this.socketFile = socketFile;
  }

  /**
   * Records the outcome of one distribute cycle and notifies when the Handler changed anything on this machine. This
   * is the distribute loop's observer, so it never throws.
   *
   * @param summary The distribute summary.
   * @param locations The number of Locations the scan found on this machine.
   */
  public void distributed(DistributeThread.Summary summary, int locations) {
    try {
      lastRun = Instant.now();
      this.locations = locations;

      if (summary.applied() > 0 || summary.conflict() > 0 || summary.failed() > 0) {
        lastChanges = new Changes(summary.applied(), summary.conflict(), summary.failed(), lastRun);
      }

      broadcast(status());
      if (summary.applied() > 0) {
        broadcast(notification("Applied changes to " + summary.applied()
            + (summary.applied() == 1 ? " Location" : " Locations")
            + (summary.clean() ? "" : " (" + changes(summary) + ")")));
      }
    } catch (Throwable t) {
      LOG.log(System.Logger.Level.WARNING, "Unable to publish a distribute cycle to the tray feed", t);
    }
  }

  /**
   * Classifies one briefing result, records the run, and notifies when The Agency sent new Briefs. This is the
   * receive loop's observer, so it never throws.
   *
   * @param result The result of the receive cycle's briefing call.
   */
  public void received(BriefingResult result) {
    try {
      lastRun = Instant.now();
      TrayState next = TrayState.classify(result);
      if (next == TrayState.HEALTHY) {
        lastResponse = lastRun;
      }

      synchronized (this) {
        // Leaving LOGGED_OUT re-arms the announcement, so each logged-out episode notifies once rather than once ever
        if (next != TrayState.LOGGED_OUT) {
          loggedOutAnnounced = false;
        }
        state = next;
      }

      broadcast(status());
      if (result instanceof BriefingResult.Updated updated && !updated.briefs().isEmpty()) {
        int count = updated.briefs().size();
        broadcast(notification(count == 1 ? "Received 1 updated Brief from The Agency"
                                          : "Received " + count + " updated Briefs from The Agency"));
      }
      announceLoggedOut();
    } catch (Throwable t) {
      LOG.log(System.Logger.Level.WARNING, "Unable to publish a receive cycle to the tray feed", t);
    }
  }

  /**
   * Binds the socket and starts accepting clients, showing the given initial state until the first cycle reports. A
   * no-op that logs when the socket cannot be bound.
   *
   * @param initial The state to report until the first briefing result arrives.
   */
  public void start(TrayState initial) {
    state = initial;
    try {
      Files.createDirectories(socketFile.getParent());

      // A previous daemon that was killed rather than shut down leaves its socket file behind, and binding over it
      // fails rather than reclaiming it
      Files.deleteIfExists(socketFile);

      server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
      server.bind(UnixDomainSocketAddress.of(socketFile));
      bound = true;
      try {
        Files.setPosixFilePermissions(socketFile, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException e) {
        // A filesystem without POSIX permissions has no tighter setting to apply
      }

      Thread.ofVirtual().name("handler-tray-feed").start(this::accept);
    } catch (Throwable t) {
      LOG.log(System.Logger.Level.WARNING, "Unable to serve the tray feed on [" + socketFile + "]", t);
      stop();
    }
  }

  /**
   * Stops accepting clients, disconnects the connected ones, and removes the socket file. A no-op when the feed never
   * started — every CLI process wires a feed and shuts it down on exit, and the socket file it never bound belongs to
   * the running daemon, so deleting it here would cut the tray off from a perfectly healthy daemon.
   */
  public void stop() {
    ServerSocketChannel local = server;
    server = null;
    if (local != null) {
      try {
        local.close();
      } catch (IOException e) {
        // Closing is best-effort; the process is exiting
      }
    }

    synchronized (this) {
      for (SocketChannel client : clients) {
        try {
          client.close();
        } catch (IOException e) {
          // Closing is best-effort; the client will notice either way
        }
      }
      clients.clear();
    }

    if (bound) {
      bound = false;
      try {
        Files.deleteIfExists(socketFile);
      } catch (IOException e) {
        LOG.log(System.Logger.Level.WARNING, "Unable to remove the tray feed socket [" + socketFile + "]", e);
      }
    }
  }

  private void accept() {
    ServerSocketChannel local = server;
    try {
      while (local != null && local.isOpen()) {
        SocketChannel client = local.accept();

        // The snapshot goes out under the broadcast lock so a client that connects mid-cycle can never miss the
        // update or receive it out of order. A pending logged-out announcement follows the snapshot — the daemon may
        // have entered LOGGED_OUT before any tray was connected to hear it.
        synchronized (this) {
          if (send(client, status())) {
            clients.add(client);
            if (state == TrayState.LOGGED_OUT && !loggedOutAnnounced) {
              loggedOutAnnounced = send(client, notification(LOGGED_OUT_NOTIFICATION));
            }
          }
        }
      }
    } catch (Throwable t) {
      if (server != null) {
        LOG.log(System.Logger.Level.WARNING, "The tray feed stopped accepting clients", t);
      }
      // A closed channel here is stop() doing its job
    }
  }

  /**
   * Sends the logged-out notification at most once per logged-out episode. Delivery is what arms the flag: with no
   * tray connected, the announcement stays pending and the next client to connect receives it, so a daemon that
   * starts logged out still notifies exactly once.
   */
  private void announceLoggedOut() {
    synchronized (this) {
      if (state != TrayState.LOGGED_OUT || loggedOutAnnounced || clients.isEmpty()) {
        return;
      }

      broadcast(notification(LOGGED_OUT_NOTIFICATION));
      loggedOutAnnounced = !clients.isEmpty();
    }
  }

  private void broadcast(String line) {
    synchronized (this) {
      clients.removeIf(client -> !send(client, line));
    }
  }

  private String changes(DistributeThread.Summary summary) {
    List<String> parts = new ArrayList<>();
    if (summary.applied() > 0) {
      parts.add(summary.applied() + " applied");
    }
    if (summary.conflict() > 0) {
      parts.add(summary.conflict() + (summary.conflict() == 1 ? " conflict" : " conflicts"));
    }
    if (summary.failed() > 0) {
      parts.add(summary.failed() + " failed");
    }

    return String.join(", ", parts);
  }

  private String escape(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> escaped.append("\\\\");
        case '"' -> escaped.append("\\\"");
        default -> {
          if (c < 0x20) {
            escaped.append(String.format("\\u%04x", (int) c));
          } else {
            escaped.append(c);
          }
        }
      }
    }

    return escaped.toString();
  }

  private String notification(String message) {
    return "{\"type\":\"notification\",\"message\":\"" + escape(message) + "\"}";
  }

  private boolean send(SocketChannel client, String line) {
    try {
      ByteBuffer buffer = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
      while (buffer.hasRemaining()) {
        client.write(buffer);
      }
      return true;
    } catch (IOException e) {
      try {
        client.close();
      } catch (IOException x) {
        // Closing is best-effort; the client already failed
      }
      return false;
    }
  }

  private String status() {
    Instant response = lastResponse;
    Instant run = lastRun;
    int found = locations;
    Changes changed = lastChanges;

    StringBuilder json = new StringBuilder(160);
    json.append("{\"type\":\"status\",\"state\":\"").append(state).append('"');
    json.append(",\"lastResponse\":").append(response == null ? "null" : response.toEpochMilli());
    json.append(",\"lastRun\":").append(run == null ? "null" : run.toEpochMilli());
    json.append(",\"locations\":").append(found < 0 ? "null" : found);
    if (changed == null) {
      json.append(",\"lastChanges\":null");
    } else {
      json.append(",\"lastChanges\":{\"applied\":").append(changed.applied())
          .append(",\"conflict\":").append(changed.conflict())
          .append(",\"failed\":").append(changed.failed())
          .append(",\"at\":").append(changed.at().toEpochMilli())
          .append('}');
    }
    json.append('}');

    return json.toString();
  }

  private record Changes(int applied, int conflict, int failed, Instant at) {
  }
}

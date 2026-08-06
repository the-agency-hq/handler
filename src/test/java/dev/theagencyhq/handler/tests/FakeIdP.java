/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.lattejava.http;

/**
 * A scriptable stand-in for the FusionAuth token endpoint, backed by a real HTTP server on an ephemeral port. Unlike
 * {@link FakeAgency} this returns a body for every status, because the OAuth error body is part of what the client
 * has to handle.
 *
 * @author Brian Pontarelli
 */
public class FakeIdP implements Closeable {
  private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
  private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
  private final Queue<Scripted> scripted = new ConcurrentLinkedQueue<>();
  private int port;
  private HTTPServer server;

  @Override
  public void close() {
    if (server != null) {
      server.close();
      server = null;
    }
  }

  public List<String> paths() {
    return List.copyOf(paths);
  }

  public List<String> requestBodies() {
    return List.copyOf(requestBodies);
  }

  /**
   * Queues one response. Responses are consumed in order; when the queue empties, every further request gets a 500
   * with an empty body.
   *
   * @param status The HTTP status to return.
   * @param body   The response body, returned for every status.
   */
  public void script(int status, String body) {
    scripted.add(new Scripted(status, body));
  }

  public int start() {
    HTTPHandler handler = (req, res) -> {
      paths.add(req.getPath());
      requestBodies.add(req.hasBody() ? new String(req.getBodyBytes(), StandardCharsets.UTF_8) : "");

      Scripted next = scripted.poll();
      int status = next == null ? 500 : next.status();
      String body = next == null ? "" : next.body();

      res.setStatus(status);
      if (body != null && !body.isEmpty()) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        res.setContentLength(bytes.length);
        res.getOutputStream().write(bytes);
      }
    };

    server = new HTTPServer().withHandler(handler).withListener(new HTTPListenerConfiguration(0));
    server.start();
    port = server.getActualPort();
    return port;
  }

  public String url() {
    return "http://localhost:" + port;
  }

  private record Scripted(int status, String body) {
  }
}

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.lattejava.http;

/**
 * A scriptable stand-in for The Agency, backed by a real HTTP server on an ephemeral port.
 *
 * @author Brian Pontarelli
 */
public class FakeAgency implements Closeable {
  private final List<String> authorizationHeaders = Collections.synchronizedList(new ArrayList<>());
  private final List<String> contentTypes = Collections.synchronizedList(new ArrayList<>());
  private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
  private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
  private final Queue<Scripted> scripted = new ConcurrentLinkedQueue<>();
  private int port;
  private HTTPServer server;

  public List<String> authorizationHeaders() {
    return List.copyOf(authorizationHeaders);
  }

  @Override
  public void close() {
    if (server != null) {
      server.close();
      server = null;
    }
  }

  public List<String> contentTypes() {
    return List.copyOf(contentTypes);
  }

  public List<String> paths() {
    return List.copyOf(paths);
  }

  public List<String> requestBodies() {
    return List.copyOf(requestBodies);
  }

  /**
   * Queues one response. Responses are consumed in order; when the queue empties, every further request gets a 500.
   *
   * @param status The HTTP status to return.
   * @param body   The response body, ignored for statuses that carry none.
   */
  public void script(int status, String body) {
    scripted.add(new Scripted(status, body));
  }

  public int start() {
    HTTPHandler handler = (req, res) -> {
      paths.add(req.getPath());
      String authorization = req.getHeader("Authorization");
      authorizationHeaders.add(authorization == null ? "" : authorization);
      String contentType = req.getHeader("Content-Type");
      contentTypes.add(contentType == null ? "" : contentType);
      requestBodies.add(req.hasBody() ? new String(req.getBodyBytes(), StandardCharsets.UTF_8) : "");

      Scripted next = scripted.poll();
      if (next == null) {
        res.setStatus(500);
        return;
      }

      res.setStatus(next.status());
      if (next.status() == 200 && next.body() != null && !next.body().isEmpty()) {
        byte[] bytes = next.body().getBytes(StandardCharsets.UTF_8);
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

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

import org.lattejava.http.server.HTTPListenerConfiguration;
import org.lattejava.http.server.HTTPRequest;
import org.lattejava.http.server.HTTPResponse;
import org.lattejava.http.server.HTTPServer;

/**
 * A single-use local HTTP server that listens on the loopback interface for the OAuth redirect, validates the
 * {@code state} parameter, and exposes the captured authorization code.
 * <p>
 * The server binds an ephemeral port chosen by the operating system rather than a fixed one, so a login never
 * collides with another process or with a second concurrent login attempt. Because the port is not known until
 * {@link #start()} has bound it, the redirect URI is derived from the server rather than being a constant, and the
 * IdP must authorize the whole {@code http://127.0.0.1:*&#47;callback} pattern instead of a single URL.
 * <p>
 * The host is the IPv4 loopback literal rather than {@code localhost}, per RFC 8252 section 8.3, which says using
 * {@code localhost} is NOT RECOMMENDED. The literal guarantees the server can never inadvertently bind a
 * non-loopback interface, and it skips name resolution altogether — on a dual-stack host {@code localhost} can
 * resolve to {@code ::1} for one process and {@code 127.0.0.1} for another, so a server bound by name and a browser
 * resolving the same name can end up on different addresses.
 *
 * @author Brian Pontarelli
 */
public class LoopbackServer {
  public static final String CALLBACK_PATH = "/callback";
  public static final String LOOPBACK_HOST = "127.0.0.1";
  private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
  private final String expectedState;
  private HTTPServer server;

  public LoopbackServer(String expectedState) {
    this.expectedState = expectedState;
  }

  public String awaitCode(Duration timeout) {
    try {
      return codeFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new AuthenticationException(
          "Timed out after [" + timeout.toSeconds() + "] seconds waiting for the login to complete in the browser.");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof AuthenticationException failure) {
        throw failure;
      }
      throw new AuthenticationException(
          "The login failed. Message was [" + e.getCause().getMessage() + "]", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AuthenticationException("The login was interrupted.", e);
    }
  }

  /**
   * Returns the ephemeral port the operating system assigned when the server was bound. Only valid once
   * {@link #start()} has been called.
   *
   * @return The bound port.
   */
  public int port() {
    if (server == null) {
      throw new IllegalStateException("The loopback server has not been started, so it has no port yet.");
    }

    return server.getActualPort();
  }

  /**
   * Returns the OAuth redirect URI that points at this server. Only valid once {@link #start()} has been called,
   * since the port is assigned at bind time.
   *
   * @return The redirect URI.
   */
  public String redirectURI() {
    return "http://" + LOOPBACK_HOST + ":" + port() + CALLBACK_PATH;
  }

  public void start() {
    try {
      // Port 0 asks the OS for any free ephemeral port, which getActualPort() reports back once the listener is bound.
      // Passing the IP literal to getByName keeps this an exact-address bind with no name lookup, so the bound address
      // always matches the host advertised in redirectURI().
      server = new HTTPServer().withHandler(this::handle)
                               .withListener(new HTTPListenerConfiguration(InetAddress.getByName(LOOPBACK_HOST), 0))
                               .start();
    } catch (UnknownHostException | IllegalStateException e) {
      throw new AuthenticationException(
          "Could not start the local login server on the loopback interface. Message was [" + e.getMessage() + "]", e);
    }
  }

  public void stop() {
    if (server != null) {
      server.close();
    }
  }

  /**
   * Writes the result page to the browser. It is its own method so a test can fail the write the way a closed tab
   * fails it, which is the case that still has to complete the future.
   *
   * @param out  The response body stream.
   * @param body The page.
   * @throws IOException When the browser is no longer there to receive it.
   */
  protected void write(OutputStream out, byte[] body) throws IOException {
    out.write(body);
  }

  private void handle(HTTPRequest request, HTTPResponse response) throws IOException {
    // This server routes every path to one handler rather than only a registered context, so anything that is not the
    // callback is a 404 — and must return without resolving the future. A browser asking for /favicon.ico while the
    // real redirect is still in flight would otherwise end the login with "no authorization code".
    if (!CALLBACK_PATH.equals(request.getPath())) {
      response.setStatus(404);
      response.setContentLength(0L);
      return;
    }

    // getURLParameter reads the query string only, and the values arrive already URL-decoded.
    String error = request.getURLParameter("error");
    String state = request.getURLParameter("state");

    String code = null;
    AuthenticationException failure = null;
    if (error != null) {
      failure = new AuthenticationException("Authorization failed with error [" + error + "]");
    } else if (!Objects.equals(expectedState, state)) {
      failure = new AuthenticationException(
          "The login response state did not match. This may indicate a CSRF attempt or a stale login.");
    } else if (request.getURLParameter("code") == null) {
      failure = new AuthenticationException("The login response did not contain an authorization code.");
    } else {
      code = request.getURLParameter("code");
    }

    // Send and flush the full response to the browser BEFORE completing the future. Completing the future unblocks
    // the main thread in awaitCode, which immediately stops the server in its finally block; if that happened first
    // the server would tear down while this response was still in flight and the browser would render a broken page.
    // The completion itself is in a finally, because a browser that goes away mid-response — a tab closed as the
    // redirect lands — fails the write. The authorization code has already been captured and burned by then, so
    // leaving the future uncompleted would block awaitCode for the full timeout and then blame the browser for a
    // login that actually succeeded.
    try {
      byte[] body = loadPage(failure == null).getBytes(StandardCharsets.UTF_8);
      response.setStatus(200);
      response.setContentType("text/html; charset=utf-8");
      response.setContentLength(body.length);
      try (OutputStream out = response.getOutputStream()) {
        write(out, body);
      }
    } finally {
      if (failure != null) {
        codeFuture.completeExceptionally(failure);
      } else {
        codeFuture.complete(code);
      }
    }
  }

  /**
   * Loads the page shown in the browser once the redirect lands. Both pages are entirely self-contained — inline
   * styles, no external requests — so they render on a machine with no network. They live as resources in the jar.
   *
   * @param success Whether the login succeeded.
   * @return The complete HTML document.
   */
  private String loadPage(boolean success) {
    String resource = success ? "/auth/success.html" : "/auth/error.html";
    try (InputStream is = LoopbackServer.class.getResourceAsStream(resource)) {
      if (is == null) {
        throw new AuthenticationException("Could not find the login result page resource [" + resource + "].");
      }
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AuthenticationException(
          "Could not load the login result page resource [" + resource + "]. Message was [" + e.getMessage() + "]", e);
    }
  }

}

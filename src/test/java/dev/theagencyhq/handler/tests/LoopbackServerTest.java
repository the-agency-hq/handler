/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module java.net.http;
import module dev.theagencyhq.handler;
import module org.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class LoopbackServerTest {
  private LoopbackServer server;

  @Test
  public void aBrowserThatDisappearsMidResponseStillDeliversTheCode() throws Exception {
    // Closing the tab as the redirect lands fails the response write. The code was captured and burned before that,
    // so the login has to finish rather than block awaitCode for the full timeout and then blame the browser.
    server = new LoopbackServer("expected") {
      @Override
      protected void write(OutputStream out, byte[] body) throws IOException {
        throw new IOException("the browser closed the connection");
      }
    };
    server.start();

    try {
      get(server.redirectURI() + "?code=the-code&state=expected");
    } catch (IOException expected) {
      // The client sees a truncated response, which is exactly what a disconnected browser produces
    }

    assertEquals(server.awaitCode(Duration.ofSeconds(5)), "the-code");
  }

  @Test
  public void aMatchingRedirectYieldsTheCodeAndServesAPage() throws Exception {
    start("expected");

    HttpResponse<String> response = get(server.redirectURI() + "?code=the-code&state=expected");

    assertEquals(response.statusCode(), 200);
    assertTrue(response.body().contains("<html"), "The browser must get a real page. Body was: " + response.body());
    assertEquals(server.awaitCode(Duration.ofSeconds(5)), "the-code");
  }

  @Test
  public void aRequestForAnotherPathIs404AndLeavesTheLoginWaiting() throws Exception {
    // This server routes every path to one handler, so an unrelated request — a browser probing /favicon.ico while the
    // real redirect is still in flight — must not resolve the future and end the login with "no authorization code"
    start("expected");

    assertEquals(get("http://127.0.0.1:" + server.port() + "/favicon.ico").statusCode(), 404);

    assertThrows(AuthenticationException.class, () -> server.awaitCode(Duration.ofMillis(200)));

    // Still live afterwards: the real redirect that follows is the one that counts
    LoopbackServer second = new LoopbackServer("expected");
    second.start();
    try {
      get(second.redirectURI() + "?code=the-code&state=expected");
      assertEquals(second.awaitCode(Duration.ofSeconds(5)), "the-code");
    } finally {
      second.stop();
    }
  }

  @Test
  public void aMismatchedStateFailsTheWait() throws Exception {
    start("expected");
    get(server.redirectURI() + "?code=abc&state=tampered");

    AuthenticationException e = expectThrows(AuthenticationException.class,
                                             () -> server.awaitCode(Duration.ofSeconds(5)));
    assertTrue(e.getMessage().contains("state"), "Message was: " + e.getMessage());
  }

  @Test
  public void aMissingCodeFailsTheWait() throws Exception {
    start("expected");
    get(server.redirectURI() + "?state=expected");

    assertThrows(AuthenticationException.class, () -> server.awaitCode(Duration.ofSeconds(5)));
  }

  @Test
  public void anErrorParameterFailsTheWaitAndNamesTheError() throws Exception {
    start("expected");
    get(server.redirectURI() + "?error=access_denied");

    AuthenticationException e = expectThrows(AuthenticationException.class,
                                             () -> server.awaitCode(Duration.ofSeconds(5)));
    assertTrue(e.getMessage().contains("[access_denied]"), "Message was: " + e.getMessage());
  }

  @Test
  public void portIsUnavailableBeforeStart() {
    assertThrows(IllegalStateException.class, () -> new LoopbackServer("state").port());
  }

  @AfterMethod
  public void tearDown() {
    if (server != null) {
      server.stop();
      server = null;
    }
  }

  @Test
  public void theTimeoutIsReportedInSeconds() throws Exception {
    start("expected");

    AuthenticationException e = expectThrows(AuthenticationException.class,
                                             () -> server.awaitCode(Duration.ofMillis(200)));
    assertTrue(e.getMessage().contains("Timed out"), "Message was: " + e.getMessage());
  }

  @Test
  public void twoServersBindDifferentEphemeralPortsOnTheLoopbackLiteral() {
    start("first");
    LoopbackServer second = new LoopbackServer("second");
    second.start();

    try {
      assertNotEquals(server.port(), second.port(), "Ephemeral ports must not collide");
      assertTrue(server.redirectURI().startsWith("http://127.0.0.1:"), "URI was: " + server.redirectURI());
      assertTrue(server.redirectURI().endsWith("/callback"), "URI was: " + server.redirectURI());
      assertEquals(server.redirectURI(), "http://127.0.0.1:" + server.port() + "/callback");
    } finally {
      second.stop();
    }
  }

  private HttpResponse<String> get(String url) throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private void start(String state) {
    server = new LoopbackServer(state);
    server.start();
  }
}

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import dev.theagencyhq.handler.auth.AuthConfiguration;
import dev.theagencyhq.handler.auth.AuthenticationException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class AuthConfigurationTest {
  @Test
  public void authorizeURLCarriesEveryParameterAndEncodesThem() {
    String url = new AuthConfiguration("http://localhost:9015").authorizeURL("st ate", "chal+lenge", "http://127.0.0.1:54321/callback");

    assertTrue(url.startsWith("http://localhost:9015/oauth2/authorize?response_type=code"), "URL was: " + url);
    assertTrue(url.contains("&client_id=fa83bc7c-f1c5-48af-8ecb-6c09cf766d73"), "URL was: " + url);
    assertTrue(url.contains("&redirect_uri=http%3A%2F%2F127.0.0.1%3A54321%2Fcallback"), "URL was: " + url);
    assertTrue(url.contains("&scope=openid+offline_access"), "URL was: " + url);
    assertTrue(url.contains("&code_challenge=chal%2Blenge"), "URL was: " + url);
    assertTrue(url.contains("&code_challenge_method=S256"), "URL was: " + url);
    assertTrue(url.contains("&state=st+ate"), "URL was: " + url);
  }

  @DataProvider
  public Object[][] invalidIssuers() {
    return new Object[][]{
        {"auth.theagencyhq.dev"},        // no scheme, so not absolute
        {"ftp://auth.theagencyhq.dev"},  // wrong scheme
        {"http://"},                     // no host
        {"not a url at all"}
    };
  }

  @Test(dataProvider = "invalidIssuers")
  public void invalidIssuersAreRejectedWithTheValueInTheMessage(String issuer) {
    AuthenticationException e = expectThrows(AuthenticationException.class, () -> new AuthConfiguration(issuer));
    assertTrue(e.getMessage().contains("[" + issuer + "]"), "Message was: " + e.getMessage());
  }

  @Test
  public void issuerDefaultsWhenAbsentAndStripsEveryTrailingSlash() {
    assertEquals(new AuthConfiguration(null).issuer(), "https://auth.theagencyhq.dev");
    assertEquals(new AuthConfiguration("  ").issuer(), "https://auth.theagencyhq.dev");
    assertEquals(new AuthConfiguration("http://localhost:9015///").issuer(), "http://localhost:9015");
    assertEquals(new AuthConfiguration("  http://localhost:9015  ").issuer(), "http://localhost:9015");
  }

  @Test
  public void resolveKeepsAValidIssuer() {
    assertEquals(new AuthConfiguration("http://localhost:9015/").issuer(), "http://localhost:9015");
    assertEquals(new AuthConfiguration(null).issuer(), "https://auth.theagencyhq.dev");
  }

  @Test
  public void tokenEndpointHangsOffTheIssuer() {
    assertEquals(new AuthConfiguration("http://localhost:9015").tokenEndpoint(), URI.create("http://localhost:9015/oauth2/token"));
  }
}

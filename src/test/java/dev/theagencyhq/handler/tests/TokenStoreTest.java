/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.auth.AuthenticationException;
import dev.theagencyhq.handler.auth.TokenStore;
import dev.theagencyhq.handler.auth.Tokens;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

public class TokenStoreTest extends BaseTest {
  @Test
  public void absentFileLoadsEmptyRatherThanFailing() {
    // Not logged in is a normal state, not an error
    Tokens tokens = new TokenStore(base.resolve("missing/tokens.json")).load();

    assertEquals(tokens, Tokens.EMPTY);
    assertFalse(tokens.present());
  }

  @Test
  public void aFailedWriteLeavesThePreviousFileIntact() throws IOException {
    // Root writes through the directory permission below, so the test would pass without proving anything
    if (runningAsRoot()) {
      fail("Running as root bypasses POSIX permission checks, so a failed write cannot be simulated");
    }

    TokenStore store = new TokenStore(tokensFile());
    store.store(new Tokens("keep-me", "keep-me-refresh"));

    // The temp file the atomic move relies on cannot be created in a directory the owner cannot write
    Path directory = tokensFile().getParent();
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(directory);
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));

    try {
      AuthenticationException e = expectThrows(AuthenticationException.class,
                                               () -> store.store(new Tokens("replacement", "replacement-refresh")));
      assertTrue(e.getMessage().contains("[" + tokensFile() + "]"), "Message was: " + e.getMessage());

      Tokens loaded = store.load();
      assertEquals(loaded.accessToken(), "keep-me", "The previous tokens must survive a failed write");
      assertEquals(loaded.refreshToken(), "keep-me-refresh");
    } finally {
      Files.setPosixFilePermissions(directory, original);
    }
  }

  @Test
  public void aTokenPairWithNoRefreshTokenRoundTripsAsEmpty() {
    TokenStore store = new TokenStore(tokensFile());

    store.store(new Tokens("access-only", null));

    assertEquals(store.load().accessToken(), "access-only");
    assertEquals(store.load().refreshToken(), "");
  }

  @Test
  public void clearRemovesTheFileAndReportsWhetherAnythingWasThere() {
    TokenStore store = new TokenStore(tokensFile());

    assertFalse(store.clear(), "Nothing was stored, so clear should report false");

    store.store(new Tokens("access", "refresh"));
    assertTrue(store.clear(), "A stored token should make clear report true");
    assertFalse(Files.exists(tokensFile()));
  }

  @Test
  public void storeCreatesParentDirectoriesAndRoundTrips() {
    TokenStore store = new TokenStore(tokensFile());

    store.store(new Tokens("access-1", "refresh-1"));

    Tokens loaded = store.load();
    assertEquals(loaded.accessToken(), "access-1");
    assertEquals(loaded.refreshToken(), "refresh-1");
    assertTrue(loaded.present());
  }

  @Test
  public void storeReplacesRatherThanAppends() {
    TokenStore store = new TokenStore(tokensFile());

    store.store(new Tokens("first", "first-refresh"));
    store.store(new Tokens("second", "second-refresh"));

    assertEquals(store.load().accessToken(), "second");
    assertEquals(store.load().refreshToken(), "second-refresh");
  }

  @Test
  public void storeRestrictsPermissionsToTheOwner() throws IOException {
    new TokenStore(tokensFile()).store(new Tokens("access", "refresh"));

    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tokensFile());
    assertEquals(permissions, PosixFilePermissions.fromString("rw-------"));
  }

  private Path tokensFile() {
    return base.resolve("config/tokens.json");
  }
}

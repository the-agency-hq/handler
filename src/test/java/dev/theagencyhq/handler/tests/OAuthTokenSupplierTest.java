/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class OAuthTokenSupplierTest extends BaseTest {
  private FakeIdP idp;

  @Test
  public void aMalformedTokenFileIsReportedRatherThanThrown() throws IOException {
    // A hand-edited or truncated tokens.json must become a 401 the developer can act on, not an exception thrown out
    // of the receive cycle and misdiagnosed as a local store problem
    Files.createDirectories(base.resolve("config"));
    Files.writeString(base.resolve("config/tokens.json"), "not json at all");
    OAuthTokenSupplier supplier = supplier();

    assertEquals(supplier.bearerToken(), "", "An unreadable token cannot be served, and must not throw either");
    assertFalse(supplier.refresh());
    assertEquals(idp.paths(), List.of(), "There is nothing to exchange, so the IdP must not be called");
  }

  @Test
  public void aRefreshTokenIsExchangedAndThePairIsPersisted() {
    store().store(new Tokens("old-access", "old-refresh"));
    idp.script(200, "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\"}");
    OAuthTokenSupplier supplier = supplier();

    assertEquals(supplier.bearerToken(), "old-access");
    assertTrue(supplier.refresh());
    assertEquals(supplier.bearerToken(), "new-access");

    Tokens onDisk = store().load();
    assertEquals(onDisk.accessToken(), "new-access");
    assertEquals(onDisk.refreshToken(), "new-refresh");
  }

  @Test
  public void aRejectedRefreshReportsFailureRatherThanThrowing() {
    // The daemon must survive this: it becomes a fatal 401 telling the developer to log in again
    store().store(new Tokens("old-access", "revoked-refresh"));
    idp.script(400, "{\"error\":\"invalid_grant\"}");

    assertFalse(supplier().refresh());
    assertEquals(idp.paths(), List.of("/oauth2/token"), "The rejection has to come from the IdP, not a short circuit");
  }

  @Test
  public void aTokenWrittenByAnotherProcessIsAdoptedWithoutCallingTheIdP() {
    // This is what makes `handler login` take effect in a running daemon without a restart
    store().store(new Tokens("original", "refresh"));
    OAuthTokenSupplier supplier = supplier();
    assertEquals(supplier.bearerToken(), "original");

    store().store(new Tokens("written-by-login", "new-refresh"));

    assertTrue(supplier.refresh());
    assertEquals(supplier.bearerToken(), "written-by-login");
    assertEquals(idp.paths(), List.of(), "An adopted token must not cost an IdP round trip");
  }

  @Test
  public void adoptFromDiskIgnoresThisProcessesOwnRefreshWrite() {
    store().store(new Tokens("old-access", "old-refresh"));
    idp.script(200, "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\"}");
    OAuthTokenSupplier supplier = supplier();
    assertTrue(supplier.refresh());

    // The refresh just wrote tokens.json. The watcher sees that write like any other, and this is what keeps it from
    // costing a receive cycle
    assertFalse(supplier.adoptFromDisk());
    assertEquals(supplier.bearerToken(), "new-access");
  }

  @Test
  public void adoptFromDiskKeepsTheCachedTokenWhenTheFileIsMalformed() throws IOException {
    store().store(new Tokens("original", "refresh"));
    OAuthTokenSupplier supplier = supplier();
    assertEquals(supplier.bearerToken(), "original");

    Files.writeString(base.resolve("config/tokens.json"), "not json at all");

    assertFalse(supplier.adoptFromDisk(), "An unreadable file is reported, never thrown out of the watcher");
    assertEquals(supplier.bearerToken(), "original", "The cached token is kept until a readable file replaces it");
  }

  @Test
  public void adoptFromDiskTakesALoginWrittenByAnotherProcess() {
    store().store(new Tokens("original", "refresh"));
    OAuthTokenSupplier supplier = supplier();
    assertEquals(supplier.bearerToken(), "original");

    store().store(new Tokens("written-by-login", "new-refresh"));

    assertTrue(supplier.adoptFromDisk());
    assertEquals(supplier.bearerToken(), "written-by-login", "The next request must carry the new token, not a 401");
    assertEquals(idp.paths(), List.of(), "An adopted token must not cost an IdP round trip");
  }

  @Test
  public void adoptFromDiskTakesALogout() {
    store().store(new Tokens("original", "refresh"));
    OAuthTokenSupplier supplier = supplier();
    assertEquals(supplier.bearerToken(), "original");

    store().clear();

    assertTrue(supplier.adoptFromDisk(), "A cleared file is a change the tray needs to hear about");
    assertEquals(supplier.bearerToken(), "");
    assertFalse(supplier.refresh(), "With nothing stored there is nothing to refresh");
    assertEquals(idp.paths(), List.of());
  }

  @Test
  public void adoptFromDiskWithNothingCachedAndNothingStoredIsNotAChange() {
    assertFalse(supplier().adoptFromDisk());
  }

  @Test
  public void anAbsentTokenFileIsNotAnAdoption() {
    assertFalse(supplier().refresh());
    assertEquals(idp.paths(), List.of());
  }

  @BeforeMethod
  public void setUp() {
    idp = new FakeIdP();
    idp.start();
  }

  @AfterMethod
  public void tearDown() {
    idp.close();
  }

  @Test
  public void withNoRefreshTokenRefreshFailsWithoutCallingTheIdP() {
    store().store(new Tokens("access-only", null));

    assertFalse(supplier().refresh());
    assertEquals(idp.paths(), List.of(), "The IdP must not be called when there is nothing to exchange");
  }

  private TokenStore store() {
    return new TokenStore(base.resolve("config/tokens.json"));
  }

  private OAuthTokenSupplier supplier() {
    return new OAuthTokenSupplier(store(), new OAuthClient(new AuthConfiguration(idp.url())));
  }
}

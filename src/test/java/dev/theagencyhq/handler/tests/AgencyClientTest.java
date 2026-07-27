/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.agency.BriefingResult;
import dev.theagencyhq.handler.agency.CurrentVersion;

public class AgencyClientTest extends BaseTest {
  private FakeAgency agency;

  @Test
  public void connectionRefusedIsAFailure() throws IOException {
    int closed;
    try (ServerSocket socket = new ServerSocket(0)) {
      closed = socket.getLocalPort();
    }

    BriefingResult result = new AgencyClient("http://localhost:" + closed, () -> "t").briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Failed, "Expected Failed but got " + result);
    Assert.assertFalse(((BriefingResult.Failed) result).authenticationFailure());
  }

  @Test
  public void forbiddenIsItsOwnResult() {
    agency.script(403, "");

    Assert.assertTrue(client().briefing(List.of()) instanceof BriefingResult.Forbidden);
  }

  @Test
  public void malformedResponseBodyIsAFailureNotAnException() {
    // The only branch in this class doing real exception conversion. If it regressed, a malformed response would
    // throw out of briefing(), out of the receive cycle, and end the interval loop for the life of the process.
    agency.script(200, "not json at all");

    BriefingResult result = client().briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Failed, "Expected Failed but got " + result);
    Assert.assertFalse(((BriefingResult.Failed) result).authenticationFailure());
  }

  @Test
  public void notModifiedIsItsOwnResult() {
    agency.script(304, "");

    Assert.assertTrue(client().briefing(List.of()) instanceof BriefingResult.NotModified);
  }

  @Test
  public void requestCarriesTheBearerTokenPathAndCurrentVersions() throws IOException {
    agency.script(200, Files.readString(Path.of("src/test/resources/agency/briefing-updated.json")));

    client().briefing(List.of(new CurrentVersion("42", 73, "opaque-42-73")));

    Assert.assertEquals(agency.authorizationHeaders(), List.of("Bearer test-token"));
    Assert.assertEquals(agency.contentTypes(), List.of("application/json"));
    Assert.assertEquals(agency.paths(), List.of("/api/v1/briefing"));
    String body = agency.requestBodies().getFirst();
    Assert.assertTrue(body.contains("\"organizationId\":\"42\""), "Body was: " + body);
    Assert.assertTrue(body.contains("\"version\":73"), "Body was: " + body);
    Assert.assertTrue(body.contains("\"checksum\":\"opaque-42-73\""), "Body was: " + body);
  }

  @BeforeMethod
  public void setUp() {
    agency = new FakeAgency();
    agency.start();
  }

  @AfterMethod
  public void tearDown() {
    agency.close();
  }

  @Test
  public void serverErrorIsAFailureThatIsNotAnAuthenticationFailure() {
    agency.script(500, "");

    BriefingResult result = client().briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Failed, "Expected Failed but got " + result);
    Assert.assertFalse(((BriefingResult.Failed) result).authenticationFailure());
  }

  @Test
  public void unauthorizedIsAFailureFlaggedAsAuthentication() {
    agency.script(401, "");

    BriefingResult result = client().briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Failed, "Expected Failed but got " + result);
    Assert.assertTrue(((BriefingResult.Failed) result).authenticationFailure());
  }

  @Test
  public void updatedCarriesTheEntitledSetAndTheBriefs() throws IOException {
    agency.script(200, Files.readString(Path.of("src/test/resources/agency/briefing-updated.json")));

    BriefingResult result = client().briefing(List.of());

    Assert.assertTrue(result instanceof BriefingResult.Updated, "Expected Updated but got " + result);
    BriefingResult.Updated updated = (BriefingResult.Updated) result;
    Assert.assertEquals(updated.organizationIds(), List.of("42", "43"));
    Assert.assertEquals(updated.briefs().size(), 2);
    Assert.assertEquals(updated.briefs().getFirst().organization().name(), "Acme2");
    Assert.assertEquals(updated.briefs().getFirst().version(), 73);

    // The raw capture must survive the trip through the client untouched
    String wire = Files.readString(Path.of("src/test/resources/agency/briefing-updated.json"));
    Assert.assertTrue(wire.contains(updated.briefs().getFirst().raw()));
  }

  private AgencyClient client() {
    return new AgencyClient(agency.url(), () -> "test-token");
  }
}

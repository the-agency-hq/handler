/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.cli.Init;
import dev.theagencyhq.handler.cli.OrganizationSelector;
import dev.theagencyhq.handler.location.LocationMarker;

import static org.testng.Assert.*;

public class InitTest extends BaseTest {
  private FakeAgency agency;
  private Path directory;
  private ByteArrayOutputStream output;

  @Test
  public void initAdvisesLoginWhenTheTokenIsRejected() throws IOException {
    agency.script(401, null);

    assertEquals(init("").run(), 1);

    assertTrue(output.toString().contains("handler login"), "Output was: " + output);
    assertFalse(Files.exists(markerFile()));
  }

  @Test
  public void initAsksBeforeOverwritingAndLeavesTheFileAloneOnNo() throws IOException {
    agency.script(200, organizationsJSON());
    Files.writeString(markerFile(), "original");

    assertEquals(init("n\n").run(), 0);

    assertEquals(Files.readString(markerFile()), "original");
    String printed = output.toString();
    assertTrue(printed.contains("Overwrite"), "Output was: " + printed);
    assertTrue(printed.contains("unchanged"), "Output was: " + printed);
  }

  @Test
  public void initFailsWhenNoOrganizationsAreAvailable() throws IOException {
    agency.script(200, "{\"organizations\":[]}");

    assertEquals(init("").run(), 1);

    assertTrue(output.toString().contains("do not have access to any Organizations"), "Output was: " + output);
    assertFalse(Files.exists(markerFile()));
  }

  @Test
  public void initOverwritesAfterAYes() throws IOException {
    agency.script(200, organizationsJSON());
    Files.writeString(markerFile(), "original");

    assertEquals(init("y\n\r").run(), 0);

    LocationMarker marker = LocationMarker.fromJSON(Files.readAllBytes(markerFile()));
    assertEquals(marker.organizationId(), "42");
  }

  @Test
  public void initReportsAnAgencyFailure() throws IOException {
    // Nothing scripted, so the FakeAgency answers 500
    assertEquals(init("").run(), 1);

    assertTrue(output.toString().contains("status [500]"), "Output was: " + output);
    assertFalse(Files.exists(markerFile()));
  }

  @Test
  public void initWritesTheEnteredMissionTypes() throws IOException {
    agency.script(200, organizationsJSON());

    assertEquals(init("\r Code, DOCS ,,\n").run(), 0);

    LocationMarker marker = LocationMarker.fromJSON(Files.readAllBytes(markerFile()));
    assertEquals(marker.organizationId(), "42");
    assertEquals(marker.missionTypes(), List.of("code", "docs"));
    assertTrue(output.toString().contains("Mission Types"), "Output was: " + output);
  }

  @Test
  public void initWritesTheMarkerForTheSelectedOrganization() throws IOException {
    agency.script(200, organizationsJSON());

    assertEquals(init("\u001B[B\r").run(), 0);

    // Down then Enter selects the second Organization, and the input ending means no Mission Types
    LocationMarker marker = LocationMarker.fromJSON(Files.readAllBytes(markerFile()));
    assertEquals(marker.organizationId(), "43");
    assertEquals(marker.majorVersion(), LocationMarker.SUPPORTED_MAJOR_VERSION);
    assertEquals(marker.missionTypes(), List.of());

    assertEquals(agency.paths(), List.of(AgencyClient.ORGANIZATION_PATH));
    assertEquals(agency.authorizationHeaders(), List.of("Bearer test-token"));
    assertTrue(output.toString().contains("Bravo"), "Output was: " + output);
  }

  @BeforeMethod
  public void setUp() throws IOException {
    output = new ByteArrayOutputStream();
    directory = Files.createDirectories(base.resolve("project"));
    agency = new FakeAgency();
    agency.start();
  }

  @AfterMethod
  public void tearDown() {
    agency.close();
  }

  /**
   * Builds an {@code Init} whose confirmation prompt and selector both read from the one stream {@code input} backs,
   * exactly as they share stdin in production.
   */
  private Init init(String input) {
    InputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
    PrintStream out = new PrintStream(output, true);
    AgencyClient client = new AgencyClient(agency.url(), new StubTokenSupplier("test-token"));
    OrganizationSelector selector = new OrganizationSelector(in, out, new StubTerminal());
    return new Init(client, selector, directory, in, out);
  }

  private Path markerFile() {
    return directory.resolve("agent-location.json");
  }

  private String organizationsJSON() {
    return """
        {"organizations":[{"id":"42","name":"Alpha"},{"id":"43","name":"Bravo"}]}""";
  }
}

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

public class LocationScannerTest extends BaseTest {

  @Test
  public void anUnreadableDirectoryIsSkippedWithoutAbortingTheScan() throws IOException {
    marker("readable", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    Path locked = Files.createDirectories(base.resolve("locked/inner"));
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked.getParent());
    Files.setPosixFilePermissions(locked.getParent(), PosixFilePermissions.fromString("---------"));

    try {
      assertEquals(roots(scan()), List.of(base.resolve("readable")));
    } finally {
      // Restore, or the temp tree cannot be cleaned up afterwards
      Files.setPosixFilePermissions(locked.getParent(), original);
    }
  }

  @Test
  public void badMarkersAreSkippedWithoutFailingTheScan() throws IOException {
    marker("good", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("malformed", "{\"version\":");
    marker("no-org", "{\"version\":\"1.0.0\"}");
    marker("future-format", "{\"version\":\"2.0.0\",\"organizationId\":\"42\"}");

    assertEquals(roots(scan()), List.of(base.resolve("good")));
  }

  @Test
  public void bareMajorVersionsAreAccepted() throws IOException {
    // SemVer proper requires all three components, but a marker carrying only the major is unambiguous about the one
    // thing the scanner asks of it, so it is still accepted
    marker("bare", "{\"version\":\"1\",\"organizationId\":\"42\"}");

    assertEquals(roots(scan()), List.of(base.resolve("bare")));
  }

  @Test
  public void excludedDirectoryNamesAreNeverEntered() throws IOException {
    marker("keep", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("node_modules/skipped", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("build/skipped", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("output/skipped", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker(".hidden/skipped", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");

    assertEquals(roots(scan()), List.of(base.resolve("keep")));
  }

  @Test
  public void markerFieldsLandOnTheLocation() throws IOException {
    marker("app", "{\"version\":\"1.0.0\",\"organizationId\":\" 42 \",\"missionTypes\":[\" Web \",\"LIBRARY\"]}");

    Location location = scan().getFirst();

    assertEquals(location.root(), base.resolve("app"));
    assertEquals(location.organizationId(), "42");
    assertEquals(location.missionTypes(), List.of("web", "library"));
  }

  @Test(dataProvider = "unparseableVersions")
  public void markersWithAnUnparseableVersionAreSkipped(String version) throws IOException {
    marker("good", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("bad", "{\"version\":\"" + version + "\",\"organizationId\":\"42\"}");

    assertEquals(roots(scan()), List.of(base.resolve("good")));
  }

  @Test
  public void markersWithNoVersionAtAllAreSkipped() throws IOException {
    // Distinct from an unparseable version: nothing reaches the Version constructor, so the component stays null and
    // majorVersion() is the only thing standing between that and an NPE
    marker("good", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("no-version", "{\"organizationId\":\"42\"}");

    assertEquals(roots(scan()), List.of(base.resolve("good")));
  }

  @Test
  public void symbolicLinksAreNeverFollowed() throws IOException {
    // The link MUST live under a marker-less directory. Putting it inside a Location makes the test worthless:
    // traversal prunes at the marker and never enumerates the link at all, so the test would pass even with
    // NOFOLLOW_LINKS stripped out entirely.
    Path target = marker("target", "{\"version\":\"1.0.0\",\"organizationId\":\"99\"}");
    Files.createDirectories(base.resolve("plain"));
    Files.createSymbolicLink(base.resolve("plain/link"), target);

    // Following the link would discover the same marker a second time, at base/plain/link
    assertEquals(roots(scan()), List.of(base.resolve("target")));
  }

  @Test
  public void traversalPrunesAtTheFirstMarker() throws IOException {
    marker("outer", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("outer/inner", "{\"version\":\"1.0.0\",\"organizationId\":\"43\"}");

    // A Location owns its whole subtree, so the nested marker is never seen
    assertEquals(roots(scan()), List.of(base.resolve("outer")));
  }

  @Test
  public void traversalStopsAtTheDepthCap() throws IOException {
    StringBuilder deep = new StringBuilder("d0");
    for (int i = 1; i <= LocationScanner.MAXIMUM_DEPTH + 2; i++) {
      deep.append("/d").append(i);
    }

    marker(deep.toString(), "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");
    marker("shallow", "{\"version\":\"1.0.0\",\"organizationId\":\"42\"}");

    // The deep marker is past the cap and must not be found; the shallow one still is
    assertEquals(roots(scan()), List.of(base.resolve("shallow")));
  }

  @DataProvider
  public Object[][] unparseableVersions() {
    return new Object[][]{{""}, {"not-a-version"}, {"1.x"}, {"1..0"}, {"1.0.0.0"}};
  }

  private Path marker(String relative, String json) throws IOException {
    Path directory = Files.createDirectories(base.resolve(relative));
    Files.writeString(directory.resolve("agent-location.json"), json);
    return directory;
  }

  private List<Path> roots(List<Location> locations) {
    return locations.stream().map(Location::root).sorted().toList();
  }

  private List<Location> scan() {
    HandlerConfig config = new HandlerConfig(base.toString(), null, null, null, 0, 0);
    return new LocationScanner(config).scan();
  }
}

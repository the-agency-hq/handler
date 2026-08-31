/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import org.lattejava.version.Version;

import static org.testng.Assert.*;

// Disambiguates org.lattejava.version.Version from org.testng.internal.Version, both pulled in by the module imports

public class MissionTypesTest extends BaseTest {
  @Test
  public void markerNormalizationMakesMatchingCaseInsensitive() {
    LocationMarker marker = new LocationMarker(new Version("1.0.0"), " 42 ", List.of(" Web ", "LIBRARY"), null);

    assertEquals(marker.organizationId(), "42");
    assertEquals(marker.missionTypes(), List.of("web", "library"));
    assertTrue(MissionTypes.includes(List.of("web"), marker.missionTypes()));
  }

  @Test(dataProvider = "truthTable")
  public void truthTable(List<String> fileTypes, List<String> locationTypes, boolean expected) {
    assertEquals(MissionTypes.includes(fileTypes, locationTypes), expected);
  }

  @DataProvider
  public Object[][] truthTable() {
    // idea.md "## Mission Types", all 15 rows in order. A dash in the source table is an empty list here.
    // @formatter:off
    return new Object[][]{
        //  File Mission Type(s)                Location Mission Type(s)                   Include?
        {List.of(),                             List.of(),                                 true},
        {List.of(),                             List.of("web"),                            true},
        {List.of(),                             List.of("web", "library"),                 true},
        {List.of("web"),                        List.of(),                                 true},
        {List.of("web"),                        List.of("web"),                            true},
        {List.of("web"),                        List.of("web", "library"),                 true},
        {List.of("web"),                        List.of("framework"),                      false},
        {List.of("web"),                        List.of("web", "framework"),               true},
        {List.of("web", "library"),             List.of(),                                 true},
        {List.of("web", "library"),             List.of("web"),                            true},
        {List.of("web", "library"),             List.of("web", "library"),                 true},
        {List.of("web", "library"),             List.of("library"),                        true},
        {List.of("web", "library"),             List.of("framework"),                      false},
        {List.of("web", "library"),             List.of("framework", "web"),               true},
        {List.of("web", "library"),             List.of("framework", "library"),           true}
    };
    // @formatter:on
  }
}

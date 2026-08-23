/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class BriefTest extends BaseTest {
  @Test
  public void base64ContentDecodesToRawBytes() {
    BriefFile file = new BriefFile("logo.png", "base64", null, "AAEC", null, null);

    assertEquals(file.decoded(), new byte[]{0, 1, 2});
  }

  @Test(dataProvider = "fixtures")
  public void capturedRawIsALiteralSubstringOfTheWire(String fixture, int expectedBriefs) throws IOException {
    byte[] wire = fixture(fixture);
    String wireText = new String(wire, StandardCharsets.UTF_8);
    List<Brief> briefs = briefs(wire);

    // Without this the loop below is vacuous, and a fixture that stopped yielding Briefs would silently lose all of
    // its coverage while still reporting green
    assertEquals(briefs.size(), expectedBriefs, "Fixture [" + fixture + "] yielded the wrong Brief count");

    for (Brief brief : briefs) {
      assertTrue(brief.raw().startsWith("{") && brief.raw().endsWith("}"), "raw must span brace to brace: [" + brief.raw() + "]");
      assertTrue(wireText.contains(brief.raw()), "raw is not verbatim from the wire, so it was re-serialized: [" + brief.raw() + "]");
    }
  }

  @Test
  public void compactFixtureCapturesTheExactExpectedText() throws IOException {
    // An exact-text positive control against off-by-one framing. Note it has no capture-vs-re-serialize power on its
    // own: the generated writer emits keys in record order with no whitespace, which is exactly this fixture's shape,
    // so a re-serializing implementation would also pass. That discrimination lives in the substring test running
    // against the two pretty-printed fixtures — which is why those two must stay pretty-printed.
    String expected = "{\"checksum\":\"c\",\"organization\":{\"id\":\"45\",\"name\":\"N\"},\"version\":2,\"files\":[]}";
    List<Brief> briefs = briefs(fixture("briefing-compact.json"));

    assertEquals(briefs.size(), 1);
    assertEquals(briefs.getFirst().raw(), expected);
  }

  @Test
  public void defaultsAreAppliedWhenFieldsAreAbsent() {
    BriefFile file = new BriefFile(".claude/a.md", null, null, "x", null, null);

    assertEquals(file.encoding(), "text");
    assertEquals(file.mode(), "r--------");
    assertEquals(file.missionTypes(), List.of());
  }

  @DataProvider
  public Object[][] fixtures() {
    return new Object[][]{{"briefing-updated.json", 2}, {"briefing-tricky.json", 1}, {"briefing-compact.json", 1}};
  }

  @DataProvider
  public Object[][] invalidOrganizationIds() {
    return new Object[][]{{"../evil"}, {"/absolute"}, {"a/b"}, {"."}, {".."}};
  }

  @Test
  public void missionTypesAreTrimmedAndLowercased() {
    BriefFile file = new BriefFile(".claude/a.md", null, null, "x", null, List.of("  Web ", "LIBRARY"));

    assertEquals(file.missionTypes(), List.of("web", "library"));
  }

  @Test(dataProvider = "modes")
  public void modeParsesSymbolicAndRejectsAnythingElse(String mode, String expected) {
    BriefFile file = new BriefFile(".claude/a.md", null, mode, "x", null, null);
    if (expected == null) {
      Assert.expectThrows(IllegalArgumentException.class, file::posixMode);
    } else {
      assertEquals(file.posixMode(), PosixFilePermissions.fromString(expected));
    }
  }

  @DataProvider
  public Object[][] modes() {
    return new Object[][]{
        {"r--------", "r--------"},
        {"rw-------", "rw-------"},
        {"rwxr-xr-x", "rwxr-xr-x"},
        {"---------", "---------"},
        {"rwxrwxrwx", "rwxrwxrwx"},
        {"rwsr-xr-x", null},      // setuid - PosixFilePermission cannot represent it, so it must not be accepted
        {"rwxr-sr-x", null},      // setgid
        {"rwxrwxrwt", null},      // sticky
        {"0400", null},           // octal is no longer the wire format
        {"rw-r--r", null},        // too short
        {"rw-r--r---", null},     // too long
        {"rw-r--rw", null},       // wrong length after a plausible-looking prefix
        {"xwrxwrxwr", null},      // right alphabet, wrong positions
        {"not-a-mode", null}
    };
  }

  @Test
  public void organizationIdAcceptsANormalOrEmptyValue() {
    assertEquals(new Organization("42", "Org").id(), "42");
    assertEquals(new Organization("", "Org").id(), "");
    assertEquals(new Organization(null, "Org").id(), "");
  }

  @Test(dataProvider = "invalidOrganizationIds")
  public void organizationIdMustBeASinglePathSegment(String id) {
    // Organization.id is server-controlled and reaches FileBriefStore's storeRoot.resolve(). An absolute or
    // multi-segment id is an arbitrary-write primitive outside the store, so the compact constructor must reject it.
    Assert.expectThrows(IllegalArgumentException.class, () -> new Organization(id, "Org"));
  }

  @Test(dataProvider = "fixtures")
  public void reparsingRawYieldsAnEqualBrief(String fixture, int expectedBriefs) throws IOException {
    // The strongest available check: raw is a complete, valid, self-describing Brief document. Because the reparsed
    // Brief captures the same text into its own raw component, the two records must be exactly equal.
    List<Brief> briefs = briefs(fixture(fixture));

    // Without this the loop below is vacuous, and a fixture that stopped yielding Briefs would silently lose all of
    // its coverage while still reporting green
    assertEquals(briefs.size(), expectedBriefs, "Fixture [" + fixture + "] yielded the wrong Brief count");

    for (Brief brief : briefs) {
      assertEquals(Brief.fromJSON(brief.rawBytes()), brief);
    }
  }

  private List<Brief> briefs(byte[] wire) {
    // @JSONRaw captures at any nesting depth, so the Briefs come out of the response wrapper already carrying their own
    // verbatim text
    return BriefingResponse.fromJSON(wire).briefs();
  }
}

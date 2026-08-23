/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import static org.testng.Assert.*;

public class OrganizationSelectorTest {
  private final List<Organization> organizations = List.of(new Organization("1", "Alpha"),
                                                           new Organization("2", "Bravo"),
                                                           new Organization("3", "Charlie"));

  private ByteArrayOutputStream output;
  private StubTerminal terminal;

  @Test
  public void arrowDownMovesTheHighlight() throws IOException {
    assertEquals(select("\u001B[B\r").name(), "Bravo");
  }

  @Test
  public void arrowUpWrapsToTheBottom() throws IOException {
    assertEquals(select("\u001B[A\r").name(), "Charlie");
  }

  @Test
  public void arrowDownWrapsBackToTheTop() throws IOException {
    assertEquals(select("\u001B[B\u001B[B\u001B[B\r").name(), "Alpha");
  }

  @Test
  public void endOfInputCancelsAndStillRestoresTheTerminal() throws IOException {
    assertNull(select(""));
    assertTrue(terminal.restored, "A cancelled selector that leaves the terminal raw breaks the user's shell");
  }

  @Test
  public void enterReturnsTheHighlightedOrganization() throws IOException {
    assertEquals(select("\r").name(), "Alpha");
    assertTrue(terminal.entered);
    assertTrue(terminal.restored);
  }

  @Test
  public void qCancels() throws IOException {
    assertNull(select("q"));
    assertTrue(terminal.restored);
  }

  @BeforeMethod
  public void setUp() {
    output = new ByteArrayOutputStream();
    terminal = new StubTerminal();
  }

  @Test
  public void theMenuHighlightsExactlyTheSelectedRow() throws IOException {
    select("\r");

    String printed = output.toString();
    assertTrue(printed.contains("\u001B[104;30m> Alpha\u001B[0m"), "Output was: " + printed);
    assertTrue(printed.contains("  Bravo"), "Output was: " + printed);
    assertFalse(printed.contains("\u001B[104;30m> Bravo"), "Output was: " + printed);
  }

  @Test
  public void unrecognizedKeysAreIgnored() throws IOException {
    assertEquals(select("zx7\u001B[C\r").name(), "Alpha");
  }

  private Organization select(String input) throws IOException {
    OrganizationSelector selector = new OrganizationSelector(
        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(output, true), terminal);
    return selector.select(organizations);
  }
}

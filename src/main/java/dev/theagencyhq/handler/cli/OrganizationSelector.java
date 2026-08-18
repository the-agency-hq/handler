/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;

import dev.theagencyhq.handler.brief.Organization;

/**
 * An interactive single-choice menu: the arrow keys move the highlight, Enter confirms, and {@code q} or Ctrl-C
 * cancels. The menu redraws in place using ANSI escapes rather than scrolling a new copy per key press.
 *
 * @author Brian Pontarelli
 */
public class OrganizationSelector {
  private static final String CLEAR_LINE = "\r\u001B[2K";
  private static final String HIGHLIGHT = "\u001B[104;30m";
  private static final String RESET = "\u001B[0m";

  private final InputStream in;
  private final PrintStream out;
  private final Terminal terminal;

  public OrganizationSelector(InputStream in, PrintStream out, Terminal terminal) {
    this.in = in;
    this.out = out;
    this.terminal = terminal;
  }

  /**
   * Runs the menu until the user confirms or cancels. The terminal is restored on every exit path.
   *
   * @param organizations The choices, never empty.
   * @return The chosen Organization, or null when the user cancelled or the input ended.
   * @throws IOException When stdin is not an interactive terminal or reading it fails.
   */
  public Organization select(List<Organization> organizations) throws IOException {
    terminal.enterRawMode();
    try {
      return run(organizations);
    } finally {
      terminal.restore();
    }
  }

  private String label(Organization organization) {
    return organization.name().isEmpty() ? organization.id() : organization.name();
  }

  private void render(List<Organization> organizations, int selected, boolean first) {
    if (!first) {
      // Cursor up over the previously drawn menu so this render overwrites it
      out.print("\u001B[" + organizations.size() + "A");
    }

    for (int i = 0; i < organizations.size(); i++) {
      String label = label(organizations.get(i));
      out.print(CLEAR_LINE + (i == selected ? HIGHLIGHT + "> " + label + RESET : "  " + label) + "\n");
    }
  }

  private Organization run(List<Organization> organizations) throws IOException {
    int count = organizations.size();
    int selected = 0;
    out.println("Select an Organization (arrow keys move, Enter confirms, q cancels):");
    render(organizations, selected, true);

    while (true) {
      int key = in.read();
      switch (key) {
        case -1, 'q', 3, 4 -> {   // EOF, q, Ctrl-C, Ctrl-D
          return null;
        }
        case '\r', '\n' -> {
          return organizations.get(selected);
        }
        case 27 -> {              // ESC — an arrow key arrives as ESC [ A/B
          if (in.read() != '[') {
            continue;
          }

          int arrow = in.read();
          if (arrow == 'A') {
            selected = (selected - 1 + count) % count;
          } else if (arrow == 'B') {
            selected = (selected + 1) % count;
          } else {
            continue;
          }

          render(organizations, selected, false);
        }
        default -> {
        }
      }
    }
  }
}

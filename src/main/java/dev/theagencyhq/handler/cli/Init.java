/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * The {@code init} subcommand: asks The Agency which Organizations the user has access to, lets the user pick one,
 * asks for the optional Mission Types and agent types the Location accepts, and writes the
 * {@code agent-location.json} marker into the directory the command was run from. An existing marker is only replaced after an explicit confirmation.
 *
 * @author Brian Pontarelli
 */
public class Init {
  private final AgencyClient agency;
  private final Path directory;
  private final InputStream in;
  private final PrintStream out;
  private final OrganizationSelector selector;

  public Init(AgencyClient agency, OrganizationSelector selector, Path directory, InputStream in, PrintStream out) {
    this.agency = agency;
    this.selector = selector;
    this.directory = directory;
    this.in = in;
    this.out = out;
  }

  public int run() {
    List<Organization> organizations;
    switch (agency.organizations()) {
      case OrganizationResult.Failed(String reason) -> {
        out.println(reason);
        return 1;
      }
      case OrganizationResult.Loaded(List<Organization> loaded) -> organizations = loaded;
    }

    if (organizations.isEmpty()) {
      out.println("You do not have access to any Organizations in The Agency. Ask an administrator to add you to one,"
          + " then run [handler init] again.");
      return 1;
    }

    Path markerFile = directory.resolve(LocationScanner.MARKER_FILENAME);
    if (Files.exists(markerFile) && !confirmOverwrite(markerFile)) {
      out.println("Leaving [" + markerFile + "] unchanged.");
      return 0;
    }

    Organization organization;
    try {
      organization = selector.select(organizations);
    } catch (IOException e) {
      out.println("Unable to run the Organization menu: " + e.getMessage());
      return 1;
    }

    if (organization == null) {
      out.println("No Organization selected.");
      return 1;
    }

    List<String> missionTypes;
    try {
      missionTypes = askMissionTypes();
    } catch (IOException e) {
      out.println("Unable to read the Mission Types: " + e.getMessage());
      return 1;
    }

    List<String> agentTypes;
    try {
      agentTypes = askAgentTypes();
    } catch (IOException e) {
      out.println("Unable to read the agent types: " + e.getMessage());
      return 1;
    }

    LocationMarker marker = LocationMarker.supported(organization.id(), missionTypes, agentTypes);
    try {
      Files.writeString(markerFile, marker.toPrettyString() + "\n");
    } catch (IOException e) {
      out.println("Unable to write [" + markerFile + "]: " + e.getMessage());
      return 1;
    }

    out.println("Wrote [" + markerFile + "] for Organization ["
        + (organization.name().isEmpty() ? organization.id() : organization.name()) + "].");
    return 0;
  }

  /**
   * Asks for the agent types the Location accepts, each naming a dot-directory such as {@code claude} for
   * {@code .claude/}.
   *
   * @return The entered types, or an empty list when the user entered nothing or the input ended, which means the
   *     Location accepts every agent type.
   */
  private List<String> askAgentTypes() throws IOException {
    out.print("Agent types for this Location (comma separated, e.g. claude, codex, agents; blank accepts all): ");
    return list(readLine());
  }

  /**
   * Asks for the Mission Types the Location accepts.
   *
   * @return The entered types, or an empty list when the user entered nothing or the input ended, which means the
   *     Location accepts every Mission Type.
   */
  private List<String> askMissionTypes() throws IOException {
    out.print("Mission Types for this Location (comma separated, blank accepts all): ");
    return list(readLine());
  }

  private boolean confirmOverwrite(Path markerFile) {
    out.print("The file [" + markerFile + "] already exists. Overwrite it? [y/N] ");
    String answer;
    try {
      answer = readLine();
    } catch (IOException e) {
      return false;
    }

    return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
  }

  /**
   * @param answer A comma separated answer, or null when the input ended.
   * @return The non-blank entries, trimmed.
   */
  private List<String> list(String answer) {
    if (answer == null || answer.isEmpty()) {
      return List.of();
    }

    return Stream.of(answer.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
  }

  /**
   * Reads one line byte-by-byte rather than through a BufferedReader, which would read ahead and swallow the key
   * presses the selector needs right after the confirmation.
   *
   * @return The line without its terminator, trimmed, or null when the input ended before any line arrived.
   */
  private String readLine() throws IOException {
    StringBuilder line = new StringBuilder();
    int c;
    while ((c = in.read()) != -1 && c != '\n') {
      line.append((char) c);
    }

    if (c == -1 && line.isEmpty()) {
      return null;
    }

    return line.toString().trim();
  }
}

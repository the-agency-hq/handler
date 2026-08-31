/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.location;

import module java.base;

/**
 * Agent type filtering. An agent type names the dot-directory at the Location root that the Agent reads from, so
 * {@code claude} is {@code .claude/} and {@code agents} is {@code .agents/}. A few Agents read from a directory that
 * is not their own name, so {@link #ALIASES} lets a Location say {@code copilot} or {@code github} and mean the same
 * thing. A Location that lists no agent types accepts every file.
 *
 * @author Brian Pontarelli
 */
public final class AgentTypes {
  /**
   * Agent names whose directory is not {@code .{name}/}, mapped to the directory name. The directory name itself is
   * always accepted too.
   */
  public static final Map<String, String> ALIASES = Map.of(
      "antigravity", "agents",
      "cline", "clinerules",
      "copilot", "github",
      "kimi", "kimi-code");

  private AgentTypes() {
  }

  /**
   * @param relativePath A validated, normalized Brief file path relative to the Location root.
   * @return The agent type the file belongs to, or empty when the file is not inside a dot-directory at the root —
   *     a root-level file, or one under a directory with no leading dot, belongs to no Agent.
   */
  public static Optional<String> agentType(Path relativePath) {
    if (relativePath.getNameCount() < 2) {
      return Optional.empty();
    }

    String directory = relativePath.getName(0).toString();
    if (!directory.startsWith(".")) {
      return Optional.empty();
    }

    String type = normalize(directory);
    return type.isEmpty() ? Optional.empty() : Optional.of(type);
  }

  /**
   * @param type A normalized agent type as a Location declares it.
   * @return The name of the dot-directory that Agent reads from, without the dot: the alias target when {@code type}
   *     is in {@link #ALIASES}, otherwise {@code type} itself.
   */
  public static String directory(String type) {
    return ALIASES.getOrDefault(type, type);
  }

  /**
   * @param relativePath  A validated, normalized Brief file path relative to the Location root.
   * @param locationTypes The agent types the Location declares, already normalized, or empty for "accepts everything."
   * @return True if the file belongs in the Location.
   */
  public static boolean includes(Path relativePath, List<String> locationTypes) {
    if (locationTypes.isEmpty()) {
      return true;
    }

    Optional<String> type = agentType(relativePath);
    return type.isEmpty() || locationTypes.stream().map(AgentTypes::directory).anyMatch(type.get()::equals);
  }

  /**
   * @param types The agent types as entered, or null.
   * @return The types trimmed, lowercased, and without a leading dot, so {@code .Claude} and {@code claude} match the
   *     same directory. Blank entries are dropped.
   */
  public static List<String> normalize(List<String> types) {
    if (types == null) {
      return List.of();
    }

    return types.stream().map(AgentTypes::normalize).filter(type -> !type.isEmpty()).toList();
  }

  private static String normalize(String type) {
    String normalized = type.trim().toLowerCase(Locale.ROOT);
    while (normalized.startsWith(".")) {
      normalized = normalized.substring(1);
    }

    return normalized.trim();
  }
}

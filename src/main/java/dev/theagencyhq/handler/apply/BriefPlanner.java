/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * Turns a Brief plus a Location into an in-memory plan. Writes nothing. Any invalid path or mode fails the whole plan
 * — a Brief must never be able to write outside its Location or corrupt the Handler's own bookkeeping, and a
 * partially applied Brief is worse than a skipped one.
 *
 * @author Brian Pontarelli
 */
public class BriefPlanner {
  private static final String GIT_DIRECTORY = ".git";
  private static final String GITIGNORE = ".gitignore";

  public LocationPlan plan(StoredBrief storedBrief, Location location) {
    List<PlannedFile> files = new ArrayList<>();
    Set<Path> planned = new HashSet<>();
    for (BriefFile file : storedBrief.brief().files()) {
      if (!MissionTypes.includes(file.missionTypes(), location.missionTypes())) {
        continue;
      }

      Path relativePath = validate(file.path(), storedBrief);
      if (!AgentTypes.includes(relativePath, location.agentTypes())) {
        continue;
      }

      if (!planned.add(relativePath)) {
        // `a//b.md` and `a/b.md` normalize identically. Writing both would double-write the file and put two
        // entries in the manifest, so the second teardown pass would try to delete an already-deleted path.
        throw new InvalidPlanException(describe(storedBrief) + " plans the same path twice [" + relativePath + "]");
      }

      try {
        files.add(new PlannedFile(relativePath, file.decoded(), file.posixMode()));
      } catch (IllegalArgumentException e) {
        throw new InvalidPlanException(describe(storedBrief) + " has an invalid file [" + file.path() + "]: "
                                       + e.getMessage(), e);
      }
    }

    files.sort(Comparator.comparing(PlannedFile::relativePath));

    // Shallowest-first so creation order is always valid
    SequencedSet<Path> directories = new LinkedHashSet<>();
    for (PlannedFile file : files) {
      Path parent = file.relativePath().getParent();
      if (parent == null) {
        continue;
      }

      List<Path> ancestors = new ArrayList<>();
      for (Path ancestor = parent; ancestor != null; ancestor = ancestor.getParent()) {
        ancestors.add(ancestor);
      }

      directories.addAll(ancestors.reversed());
    }

    return new LocationPlan(List.copyOf(files), directories);
  }

  private String describe(StoredBrief storedBrief) {
    return "Brief for Organization [" + storedBrief.organizationId() + "] version [" + storedBrief.version() + "]";
  }

  private Path validate(String rawPath, StoredBrief storedBrief) {
    if (rawPath.isEmpty()) {
      throw new InvalidPlanException(describe(storedBrief) + " has a file with an empty path");
    }

    // Checked before Path.of, so a NUL surfaces as InvalidPlanException rather than letting InvalidPathException
    // escape. A newline is the dangerous one: Manifest.append and GitExclude.add are both line-oriented and neither
    // escapes, so an embedded newline injects a standalone line into the manifest. If that line is an absolute path,
    // root.resolve() returns it unchanged and the next teardown deletes an arbitrary file anywhere on the machine.
    for (int i = 0; i < rawPath.length(); i++) {
      char character = rawPath.charAt(i);
      if (character < 0x20 || character == 0x7F) {
        throw new InvalidPlanException(describe(storedBrief) + " has a control character in the file path [" + rawPath + "]");
      }
    }

    Path path = Path.of(rawPath);
    if (path.isAbsolute()) {
      throw new InvalidPlanException(describe(storedBrief) + " has an absolute file path [" + rawPath + "]");
    }

    for (Path segment : path) {
      String name = segment.toString();
      if (name.equals("..") || name.equals(".")) {
        throw new InvalidPlanException(describe(storedBrief) + " has a relative segment in [" + rawPath + "]");
      }

      // Every segment, case-insensitively — not just the first, and not just the whole path. `tools/.git/config`
      // plants a fabricated repository whose core.fsmonitor executes on the next git call, and macOS APFS is
      // case-insensitive by default so `.GIT/hooks/pre-commit` really is `.git/hooks/pre-commit`.
      String lowered = name.toLowerCase(Locale.ROOT);
      switch (lowered) {
        case GIT_DIRECTORY -> throw new InvalidPlanException(describe(storedBrief) + " has a file path inside .git [" + rawPath + "]");
        case Manifest.FILENAME -> throw new InvalidPlanException(describe(storedBrief) + " tries to write the manifest [" + rawPath + "]");
        // Still rejected, but no longer because the Handler writes it - all Handler exclusions live in
        // .git/info/exclude now. .gitignore is committed and owned by the team, there is no merge, and the Handler
        // always wins at a managed path (§3.1 item 1), so a Brief naming it would silently replace rules the team
        // wrote and reviewed. Delivering a .gitignore is a plausible feature, but it needs deciding on its own.
        case GITIGNORE -> throw new InvalidPlanException(describe(storedBrief) + " tries to write .gitignore [" + rawPath + "]");
        // The applier stages every in-flight write here and deletes the whole directory around each apply, so a Brief
        // file planned inside it would be destroyed without explanation
        case Manifest.STAGING_DIRECTORY -> throw new InvalidPlanException(describe(storedBrief) + " tries to write inside the staging directory [" + rawPath + "]");
      }
    }

    Path normalized = path.normalize();
    if (normalized.toString().isEmpty() || normalized.startsWith("..")) {
      throw new InvalidPlanException(describe(storedBrief) + " has a file path that escapes the Location [" + rawPath + "]");
    }

    return normalized;
  }

  public static class InvalidPlanException extends RuntimeException {
    public InvalidPlanException(String message) {
      super(message);
    }

    public InvalidPlanException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

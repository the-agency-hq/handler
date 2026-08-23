/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;
import module dev.theagencyhq.handler;

/**
 * Applies a plan to a Location through the manifest. The manifest entry for anything is always written before the thing
 * itself exists, so a crash can leave an entry with no file — harmless, the next teardown cleans it up — but never
 * a file the Handler created and does not know about.
 *
 * @author Brian Pontarelli
 */
public class LocationApplier {
  private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
  private static final System.Logger LOG = System.getLogger(LocationApplier.class.getName());

  /**
   * @param location The Location to make match the plan.
   * @param plan     What the Location should contain. An empty plan is a pure teardown.
   * @param force    True to adopt unmanaged files at planned paths instead of skipping the Location.
   * @return The outcome. This method never throws.
   */
  public ApplyResult apply(Location location, LocationPlan plan, boolean force) {
    Path root = location.root();
    try {
      GitExclude gitExclude = new GitExclude(root);
      Manifest manifest = bootstrap(root, gitExclude);

      List<Path> conflicts = conflicts(root, plan, manifest.entries());
      if (!conflicts.isEmpty() && !force) {
        LOG.log(System.Logger.Level.ERROR,
                "Location [{0}] has unmanaged files at planned paths {1} and was skipped. Run [handler sync --force] to"
                + " adopt them.", root, conflicts);
        return ApplyResult.SKIPPED_CONFLICT;
      }

      if (!changed(root, plan, manifest.entries())) {
        return ApplyResult.UNCHANGED;
      }

      List<Manifest.Entry> retained = teardown(root, manifest, gitExclude);
      // A single flushed write, not clear() followed by re-appending: entries teardown could not remove must
      // survive, or a Handler-created directory the developer has since put a file into becomes permanently
      // unrecorded, and a kill between a clear and the re-append would lose them entirely — the manifest turning
      // into a subset of disk is exactly what reset() exists to prevent.
      manifest.reset(retained);
      write(root, plan, manifest, gitExclude);

      return ApplyResult.APPLIED;
    } catch (Manifest.UnsupportedManifestException e) {
      LOG.log(System.Logger.Level.ERROR, "Location [" + root + "] has an unreadable manifest and was skipped", e);
      return ApplyResult.SKIPPED_CONFLICT;
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "Unable to apply to Location [" + root + "]", e);
      return ApplyResult.FAILED;
    }
  }

  /**
   * Computes what {@link #apply} would do without writing anything at all — not even the manifest bootstrap. This is
   * what {@code handler status} uses, so it must stay a pure read.
   *
   * @param location The Location to inspect.
   * @param plan     What the Location should contain.
   * @return The Location's state.
   */
  public LocationState inspect(Location location, LocationPlan plan) {
    Path root = location.root();
    try {
      List<Manifest.Entry> entries = FileManifest.peek(root.resolve(Manifest.FILENAME));
      if (!conflicts(root, plan, entries).isEmpty()) {
        return LocationState.CONFLICT;
      }

      return changed(root, plan, entries) ? LocationState.CHANGED : LocationState.UNCHANGED;
    } catch (Manifest.UnsupportedManifestException e) {
      return LocationState.CONFLICT;
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.DEBUG, "Unable to inspect Location [" + root + "]", e);
      return LocationState.UNREADABLE;
    }
  }

  private Manifest bootstrap(Path root, GitExclude gitExclude) {
    // Every orphan from a crashed write is inside the staging directory, so one delete collects all of them
    // regardless of which files the current Brief still names. Done here rather than in write() so an Organization
    // whose Locations are all UNCHANGED still cleans up after a crash. It costs one existence check on the fast
    // path and never invokes git, which ensureIgnored below would.
    Path staging = root.resolve(Manifest.STAGING_DIRECTORY);
    try {
      deleteRecursively(staging);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to remove the stale staging directory [" + staging + "]", e);
    }

    Path manifestFile = root.resolve(Manifest.FILENAME);
    boolean fresh = !Files.exists(manifestFile, LinkOption.NOFOLLOW_LINKS);
    Manifest manifest = new FileManifest(manifestFile);
    if (fresh) {
      gitExclude.ensureExcluded(Manifest.FILENAME);
    }

    return manifest;
  }

  private boolean changed(Path root, LocationPlan plan, List<Manifest.Entry> entries) {
    Set<Path> manifestFiles = entries.stream()
                                     .filter(entry -> !entry.directory())
                                     .map(Manifest.Entry::path)
                                     .collect(Collectors.toSet());
    Set<Path> plannedFiles = plan.files().stream().map(PlannedFile::relativePath).collect(Collectors.toSet());
    if (!manifestFiles.equals(plannedFiles)) {
      return true;
    }

    for (Manifest.Entry entry : entries) {
      if (!Files.exists(root.resolve(entry.path()), LinkOption.NOFOLLOW_LINKS)) {
        return true;
      }
    }

    for (Path directory : plan.directories()) {
      if (!Files.isDirectory(root.resolve(directory), LinkOption.NOFOLLOW_LINKS)) {
        return true;
      }
    }

    for (PlannedFile file : plan.files()) {
      Path target = root.resolve(file.relativePath());
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        return true;      // a symlink or anything else standing in for the file counts as a difference
      }

      try {
        if (Files.size(target) != file.content().length) {
          return true;
        }

        if (!Files.getPosixFilePermissions(target).equals(file.mode())) {
          return true;
        }

        if (!Arrays.equals(Files.readAllBytes(target), file.content())) {
          return true;
        }
      } catch (IOException e) {
        return true;      // unreadable is a difference
      }
    }

    // Modification time plays no part
    return false;
  }

  private void clearReadOnly(Path path) {
    try {
      Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(path,
                                                                                        LinkOption.NOFOLLOW_LINKS));
      if (permissions.add(PosixFilePermission.OWNER_WRITE)) {
        Files.setPosixFilePermissions(path, permissions);
      }
    } catch (IOException e) {
      LOG.log(System.Logger.Level.DEBUG, "Unable to clear read-only on [" + path + "]", e);
    }
  }

  private List<Path> conflicts(Path root, LocationPlan plan, List<Manifest.Entry> entries) {
    Set<Path> managed = entries.stream().map(Manifest.Entry::path).collect(Collectors.toSet());
    List<Path> conflicts = new ArrayList<>();

    for (Path directory : plan.directories()) {
      Path target = root.resolve(directory);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        conflicts.add(directory);       // planned directory exists as a file
      }
    }

    for (PlannedFile file : plan.files()) {
      Path target = root.resolve(file.relativePath());
      if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }

      if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || !managed.contains(file.relativePath())) {
        conflicts.add(file.relativePath());
      }
    }

    conflicts.sort(Comparator.naturalOrder());
    return conflicts;
  }

  private void deleteRecursively(Path path) throws IOException {
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      try (Stream<Path> paths = Files.walk(path)) {
        for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
          clearReadOnly(child);
          Files.deleteIfExists(child);
        }
      }
    } else {
      clearReadOnly(path);
      Files.deleteIfExists(path);
    }
  }

  private List<Manifest.Entry> teardown(Path root, Manifest manifest, GitExclude gitExclude) {
    List<Manifest.Entry> reversed = manifest.entries().reversed();
    gitExclude.remove(reversed.stream().map(Manifest.Entry::path).toList());

    List<Manifest.Entry> retained = new ArrayList<>();
    for (Manifest.Entry entry : reversed) {
      Path target = root.resolve(entry.path());
      try {
        if (entry.directory()) {
          if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            continue;
          }

          clearReadOnly(target);
          try (DirectoryStream<Path> children = Files.newDirectoryStream(target)) {
            if (children.iterator().hasNext()) {
              LOG.log(System.Logger.Level.DEBUG, "Leaving non-empty directory [{0}] in place", target);
              retained.add(entry);
              continue;
            }
          }

          Files.delete(target);
        } else if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
          // A managed file entry now sitting on a directory is exactly what --force exists to adopt. Deleting it
          // here would throw DirectoryNotEmptyException and strand the Location at FAILED forever; leave it for
          // write()'s deleteRecursively to replace, and keep the manifest honest about it until then.
          LOG.log(System.Logger.Level.DEBUG, "Leaving directory [{0}] at a managed file path in place", target);
          retained.add(entry);
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          clearReadOnly(target);
          Files.delete(target);
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Unable to tear down [" + target + "]", e);
      }
    }

    return retained.reversed();      // restore creation order - the loop above walked in reverse
  }

  private void write(Path root, LocationPlan plan, Manifest manifest, GitExclude gitExclude) {
    // One read-modify-write for the whole Brief instead of one per file - O(N) bytes instead of O(N^2)
    gitExclude.add(plan.files().stream().map(PlannedFile::relativePath).toList());

    Path staging = root.resolve(Manifest.STAGING_DIRECTORY);
    try {
      // 0700 so an orphan is unreachable by any other user regardless of the mode it carries. Not recorded in the
      // manifest: it is transient scaffolding rather than delivered content, and it is removed below.
      Files.createDirectory(staging, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to create the staging directory [" + staging + "]", e);
    }

    // Beside .handler-manifest in info/exclude. Done here rather than in bootstrap() so the unchanged fast path
    // never forks git, which resolving the exclude file requires.
    gitExclude.ensureExcluded(Manifest.STAGING_DIRECTORY + "/");

    try {
      writeStaged(root, plan, manifest, staging);
    } finally {
      // Whether the write succeeded or threw, nothing in here is wanted. Leaving it would make the directory's mere
      // presence stop meaning "a write died partway through".
      try {
        deleteRecursively(staging);
      } catch (IOException e) {
        // The next apply's bootstrap sweeps it, so this costs a stale directory rather than the whole cycle
        LOG.log(System.Logger.Level.WARNING, "Unable to remove the staging directory [" + staging + "]", e);
      }
    }
  }

  private void writeStaged(Path root, LocationPlan plan, Manifest manifest, Path staging) {
    Set<Path> created = new HashSet<>();
    for (PlannedFile file : plan.files()) {
      Path relativePath = file.relativePath();
      List<Path> ancestors = new ArrayList<>();
      for (Path ancestor = relativePath.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
        ancestors.add(ancestor);
      }

      for (Path ancestor : ancestors.reversed()) {
        if (!created.add(ancestor)) {
          continue;
        }

        Path target = root.resolve(ancestor);
        try {
          if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            clearReadOnly(target);
            continue;
          }

          if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(target);      // adopted type conflict - force was granted to get here
          }

          // The manifest entry is written before the thing it describes exists
          manifest.append(new Manifest.Entry(ancestor, true));
          Files.createDirectory(target);
          Files.setPosixFilePermissions(target, DIRECTORY_MODE);
        } catch (IOException e) {
          throw new UncheckedIOException("Unable to create directory [" + target + "]", e);
        }
      }

      Path target = root.resolve(relativePath);
      try {
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
          deleteRecursively(target);
        }

        manifest.append(new Manifest.Entry(relativePath, false));

        // Staged in .handler-tmp/ rather than beside the target: an orphan left by a kill between the write and the
        // move never lands in a content directory, where it would be visible to a running Agent, show up untracked
        // in git status, and make the parent non-empty so teardown could never remove it. The staging directory is a
        // subtree of the Location, so this stays a same-filesystem rename and ATOMIC_MOVE always holds.
        //
        // The mode is set on the staged file, before the move. rename(2) carries the inode across, so the file
        // appears at its planned path already correct - there is no post-move window where Brief content sits at
        // the umask default. It cannot be set at creation either: the default mode is r--------, and the write
        // that follows would then be denied.
        Path temporary = staging.resolve(UUID.randomUUID().toString());
        Files.write(temporary, file.content(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(temporary, file.mode());
        clearReadOnly(target);      // a previous read-only file must not block the move
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        throw new UncheckedIOException("Unable to write [" + target + "]", e);
      }
    }
  }
}

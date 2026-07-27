/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;
import java.lang.System.Logger.*;

/**
 * Manages the Location's {@code .git/info/exclude} lines — both the Brief's files and the Handler's own bookkeeping
 * names. Every operation is line-exact and idempotent, and lines the Handler did not write are never touched.
 * <p>
 * Resolution is lazy and cached for the instance's lifetime, so constructing one per Location per cycle costs on the
 * unchanged fast path.
 * <p>
 * This method of excluding files in a Git repository is preferred over adding some of them to the {@code .gitignore}
 * file. That file would have a lot of churn if it stored the manifest information. It would also need to be modified
 * on new projects in order to add the manifest file and temp dirs used by the Handler. And finally, it would cause
 * churn on clone or forked repositories that the developer is not part of the Organization that owns the repository.
 *
 * @author Brian Pontarelli
 */
public class GitExclude {
  private static final Duration GIT_TIMEOUT = Duration.ofSeconds(2);
  private static final System.Logger LOG = System.getLogger(GitExclude.class.getName());
  private final Path locationRoot;
  private Path excludeFile;
  private boolean initialized;

  public GitExclude(Path locationRoot) {
    this.locationRoot = locationRoot;
  }

  public void add(List<Path> relativePaths) {
    initialize();
    if (excludeFile == null || relativePaths.isEmpty()) {
      return;
    }

    try {
      List<String> lines = read();
      Set<String> present = new HashSet<>(lines);
      boolean changed = false;
      for (Path relativePath : relativePaths) {
        String line = relativePath.toString();
        if (present.add(line)) {
          lines.add(line);
          changed = true;
        }
      }

      if (changed) {
        write(lines);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to update the git exclude file [" + excludeFile + "]", e);
    }
  }

  /**
   * Ensures a single literal line is present in the Location's {@code .git/info/exclude}, creating the file if needed.
   * Does nothing outside a git working tree.
   *
   * <p>Separate from {@link #add} because that renders a {@code Path}, and the Handler's own names include a directory
   * pattern with a trailing slash that a {@code Path} cannot carry.
   *
   * @param line The exact line to ensure.
   */
  public void ensureExcluded(String line) {
    initialize();
    if (excludeFile == null) {
      return;
    }

    try {
      List<String> lines = read();
      if (lines.contains(line)) {
        return;
      }

      lines.add(line);
      write(lines);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to update the git exclude file [" + excludeFile + "]", e);
    }
  }

  public void remove(List<Path> relativePaths) {
    initialize();
    if (excludeFile == null || relativePaths.isEmpty() || !Files.isRegularFile(excludeFile)) {
      return;
    }

    Set<String> doomed = relativePaths.stream().map(Path::toString).collect(Collectors.toSet());
    try {
      List<String> lines = read();
      if (lines.removeIf(doomed::contains)) {
        write(lines);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to update the git exclude file [" + excludeFile + "]", e);
    }
  }

  /**
   * @return True if the Location is inside a git working tree.
   */
  public boolean repository() {
    initialize();
    return excludeFile != null;
  }

  /**
   * Reads the exclude file, if it exists. If it doesn't exist, this returns an empty ArrayList that can be populated.
   *
   * @return An ArrayList with the contents of the file (or an empty list if the file is empty or missing).
   * @throws IOException If the read failed.
   */
  private List<String> read() throws IOException {
    return Files.isRegularFile(excludeFile) ? new ArrayList<>(Files.readAllLines(excludeFile, StandardCharsets.UTF_8)) : new ArrayList<>();
  }

  private void initialize() {
    if (initialized) {
      return;
    }

    Process process = null;
    Path output = null;
    try {
      // The child's output is redirected to a temp file rather than read via process.getInputStream() so that
      // waitFor's timeout genuinely bounds the whole call. Reading the pipe first would block until the child
      // closes stdout, and FileInputStream.read is not interruptible. Locations are applied in sequence, so a
      // hung git would wedge the entire distribute cycle - and with it the thread the Handler joins on shutdown.
      output = Files.createTempFile("handler-git-", ".out");
      process = new ProcessBuilder("git", "rev-parse", "--git-path", "info/exclude")
          .directory(locationRoot.toFile())
          .redirectOutput(output.toFile())
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();

      if (!process.waitFor(GIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        process.destroyForcibly();
        LOG.log(Level.DEBUG, "git timed out for [{0}], treating it as not a repository", locationRoot);
        return;
      }

      String resolvedOutput = Files.readString(output).strip();
      if (process.exitValue() != 0 || resolvedOutput.isEmpty()) {
        LOG.log(Level.DEBUG, "[{0}] is not a git working tree", locationRoot);
        return;
      }

      Path resolved = Path.of(resolvedOutput);
      excludeFile = resolved.isAbsolute() ? resolved : locationRoot.resolve(resolved);
    } catch (IOException e) {
      LOG.log(Level.DEBUG, "git is unavailable, treating [" + locationRoot + "] as not a repository", e);
    } catch (InterruptedException e) {
      // Consistent with the timeout branch: kill the subprocess and say why, rather than silently caching a
      // permanent "not a repository" with no trace in the log
      process.destroyForcibly();
      LOG.log(Level.DEBUG, "Interrupted resolving git for [{0}], treating it as not a repository", locationRoot);
      Thread.currentThread().interrupt();
    } finally {
      initialized = true;

      if (output != null) {
        try {
          Files.deleteIfExists(output);
        } catch (IOException ignored) {
          // The temp directory's own cleanup, if any, is the last resort - this is not worth failing resolution over
        }
      }
    }
  }

  private void write(List<String> lines) throws IOException {
    Files.createDirectories(excludeFile.getParent());
    Files.writeString(excludeFile, lines.isEmpty() ? "" : String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
  }
}

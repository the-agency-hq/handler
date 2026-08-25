/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;

/**
 * The {@code init-source} subcommand: scaffolds a Brief Source repository in the directory the command was run from,
 * in the layout The Agency's builder reads. It only creates what is missing — an existing directory or file is left
 * exactly as it was, so running it twice, or in a repository that already has content, is safe.
 *
 * @author Brian Pontarelli
 */
public class InitSource {
  /**
   * The top-level directories The Agency's builder maps, in the order they are created.
   */
  public static final List<String> DIRECTORIES = List.of("rules", "skills", "commands", "agents", "claude", "codex");

  public static final String KEEP_FILENAME = ".gitkeep";

  public static final String README_FILENAME = "README.md";

  /**
   * The bundled README, loaded from the jar. Kept out of the code so it can be edited as Markdown.
   */
  public static final String README_RESOURCE = "/init-source/README.md";

  public static final String SETTINGS_FILENAME = "the-agency-hq-settings.json";

  /**
   * The settings file The Agency looks for to tell a Brief Source apart from any other repository. The version is
   * the layout version, which is what the Agency checks for compatibility.
   */
  public static final String SETTINGS_JSON = """
      {
        "version": "1.0.0"
      }
      """;

  private final Path directory;
  private final PrintStream out;

  public InitSource(Path directory, PrintStream out) {
    this.directory = directory;
    this.out = out;
  }

  public int run() {
    String readme;
    try {
      readme = readme();
    } catch (IOException e) {
      out.println("Unable to load the bundled README: " + e.getMessage());
      return 1;
    }

    try {
      for (String name : DIRECTORIES) {
        Path dir = directory.resolve(name);
        Files.createDirectories(dir);
        write(dir.resolve(KEEP_FILENAME), "");
      }

      write(directory.resolve(SETTINGS_FILENAME), SETTINGS_JSON);
      write(directory.resolve(README_FILENAME), readme);
    } catch (IOException e) {
      out.println("Unable to scaffold the Brief Source: " + e.getMessage());
      return 1;
    }

    out.println("Scaffolded a Brief Source in [" + directory + "]. Read [" + README_FILENAME + "] to get started, then"
        + " push this repository to GitHub and connect it to your Organization in The Agency.");
    return 0;
  }

  /**
   * @return The README bundled in the jar.
   * @throws IOException When the resource is missing from the build or cannot be read.
   */
  private static String readme() throws IOException {
    try (InputStream is = InitSource.class.getResourceAsStream(README_RESOURCE)) {
      if (is == null) {
        throw new IOException("Missing resource [" + README_RESOURCE + "]");
      }

      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Writes a file only when it does not already exist, and says which happened so the user knows what the command
   * touched.
   */
  private void write(Path file, String content) throws IOException {
    Path relative = directory.relativize(file);
    if (Files.exists(file)) {
      out.println("  exists   " + relative);
      return;
    }

    Files.writeString(file, content);
    out.println("  created  " + relative);
  }
}

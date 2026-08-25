/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.*;

public class InitSourceTest extends BaseTest {
  private Path directory;
  private ByteArrayOutputStream output;

  @Test
  public void initSourceCreatesTheLayout() throws IOException {
    assertEquals(initSource().run(), 0);

    for (String name : InitSource.DIRECTORIES) {
      assertTrue(Files.isDirectory(directory.resolve(name)), "Missing directory [" + name + "]");
      assertTrue(Files.isRegularFile(directory.resolve(name).resolve(InitSource.KEEP_FILENAME)),
          "Missing placeholder in [" + name + "]");
    }
    assertEquals(InitSource.DIRECTORIES, List.of("rules", "skills", "commands", "agents", "claude", "codex"));

    // Exactly what the Agency's builder parses: a single [version] member at the supported layout version
    String settings = Files.readString(directory.resolve(InitSource.SETTINGS_FILENAME));
    assertTrue(settings.contains("\"version\": \"1.0.0\""), "Settings were: " + settings);

    String readme = Files.readString(directory.resolve(InitSource.README_FILENAME));
    assertTrue(readme.startsWith("# Brief Source"), "README was: " + readme);
    assertTrue(readme.contains(".mission-types"), "README must explain Mission Type files. README was: " + readme);
    assertTrue(readme.contains("handler init"), "README was: " + readme);

    String printed = output.toString();
    assertTrue(printed.contains("Scaffolded"), "Output was: " + printed);
    assertTrue(printed.contains("created  " + InitSource.SETTINGS_FILENAME), "Output was: " + printed);
  }

  @Test
  public void initSourceFailsWhenAPathIsNotADirectory() throws IOException {
    Files.writeString(directory.resolve("rules"), "not a directory");

    assertEquals(initSource().run(), 1);

    assertTrue(output.toString().contains("Unable to scaffold"), "Output was: " + output);
    assertFalse(Files.exists(directory.resolve(InitSource.SETTINGS_FILENAME)));
  }

  @Test
  public void initSourceLeavesExistingFilesAlone() throws IOException {
    Files.createDirectories(directory.resolve("rules"));
    Files.writeString(directory.resolve("rules/rule1.md"), "keep me");
    Files.writeString(directory.resolve(InitSource.SETTINGS_FILENAME), "{\"version\":\"1.2.3\"}");
    Files.writeString(directory.resolve(InitSource.README_FILENAME), "my readme");

    assertEquals(initSource().run(), 0);

    assertEquals(Files.readString(directory.resolve("rules/rule1.md")), "keep me");
    assertEquals(Files.readString(directory.resolve(InitSource.SETTINGS_FILENAME)), "{\"version\":\"1.2.3\"}");
    assertEquals(Files.readString(directory.resolve(InitSource.README_FILENAME)), "my readme");
    assertTrue(Files.exists(directory.resolve("rules").resolve(InitSource.KEEP_FILENAME)));
    assertTrue(Files.exists(directory.resolve("skills")));

    String printed = output.toString();
    assertTrue(printed.contains("exists   " + InitSource.SETTINGS_FILENAME), "Output was: " + printed);
    assertTrue(printed.contains("created  skills/" + InitSource.KEEP_FILENAME), "Output was: " + printed);
  }

  @Test
  public void initSourceRunsTwiceWithoutChangingAnything() throws IOException {
    assertEquals(initSource().run(), 0);
    Map<Path, byte[]> first = snapshot();

    output.reset();
    assertEquals(initSource().run(), 0);

    assertEquals(snapshot().keySet(), first.keySet());
    for (var entry : first.entrySet()) {
      assertEquals(Files.readAllBytes(entry.getKey()), entry.getValue(), "Changed [" + entry.getKey() + "]");
    }
    assertFalse(output.toString().contains("created"), "Output was: " + output);
  }

  @BeforeMethod
  public void setUp() throws IOException {
    output = new ByteArrayOutputStream();
    directory = Files.createDirectories(base.resolve("source"));
  }

  private InitSource initSource() {
    return new InitSource(directory, new PrintStream(output, true));
  }

  private Map<Path, byte[]> snapshot() throws IOException {
    Map<Path, byte[]> files = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(directory)) {
      for (Path p : walk.filter(Files::isRegularFile).toList()) {
        files.put(p, Files.readAllBytes(p));
      }
    }
    return files;
  }
}

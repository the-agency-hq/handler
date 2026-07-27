/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module org.testng;

import java.nio.file.Files;

import dev.theagencyhq.handler.brief.Brief;
import dev.theagencyhq.handler.brief.FileBriefStore;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Fixture construction shared by every test: a scratch directory, Brief and briefing-response JSON, and Locations that
 * are real git repositories.
 *
 * <p>TestNG runs a base class's {@code @BeforeMethod} before the subclass's, so {@link #base} and {@link #store} are
 * always ready by the time a subclass's own setup runs. The method is named {@code setUpBase} rather than
 * {@code setUp} deliberately — a subclass declaring {@code setUp} would otherwise override it and silently skip it.
 *
 * @author Brian Pontarelli
 */
public abstract class BaseTest {
  protected Path base;
  protected FileBriefStore store;

  @BeforeMethod
  public void setUpBase() throws IOException {
    // The simple name keeps one class's scratch directories distinguishable from another's while debugging
    base = Files.createDirectories(Path.of("build/test/" + getClass().getSimpleName() + "-" + UUID.randomUUID())
                                       .toAbsolutePath());
    store = new FileBriefStore(storeRoot());
  }

  protected Brief brief(String organizationId, int version, String... files) {
    return Brief.fromJSON(briefJSON(organizationId, version, files).getBytes(StandardCharsets.UTF_8));
  }

  protected String briefJSON(String organizationId, int version, String... files) {
    return """
        {"checksum":"opaque-%s-%d","organization":{"id":"%s","name":"Org"},"version":%d,"files":[%s]}"""
        .formatted(organizationId, version, organizationId, version, String.join(",", files));
  }

  /**
   * A Brief file whose checksum is the real SHA-256 of its content, which is what the receive path verifies.
   */
  protected String file(String path, String content) {
    return file(path, content, sha256(content));
  }

  /**
   * A Brief file with an explicit checksum, so a test can supply a deliberately wrong one.
   */
  protected String file(String path, String content, String checksum) {
    return """
        {"path":"%s","content":"%s","checksum":"%s"}""".formatted(path, content, checksum);
  }

  /**
   * Reads a frozen contract fixture from the source tree rather than the module's resources: the tests already assume
   * the project root is the working directory, and it keeps JPMS resource encapsulation out of the picture.
   */
  protected byte[] fixture(String name) throws IOException {
    return Files.readAllBytes(Path.of("src/test/resources/agency", name));
  }

  /**
   * {@code build/test/} lives inside the Handler's own git working tree, so a fixture that is not its own repository
   * makes {@code git rev-parse} resolve to THIS project and the applier writes its exclude lines into the real
   * {@code .git/info/exclude}. That has happened twice.
   *
   * <p>{@code --template=} (empty) suppresses git's stock {@code info/exclude}, which otherwise arrives with six
   * comment lines and would make every exact-content assertion depend on the local git installation's template.
   */
  protected void initRepository(Path directory) throws IOException {
    Process process;
    try {
      process = new ProcessBuilder("git", "init", "--quiet", "--template=").directory(directory.toFile()).start();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "git init timed out");
      assertEquals(process.exitValue(), 0, "git init failed");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  /**
   * Creates a Location — an {@code agent-location.json} marker inside its own git repository — under {@link
   * #locations()}.
   */
  protected Path location(String relative, String organizationId) throws IOException {
    Path directory = Files.createDirectories(locations().resolve(relative));
    Files.writeString(directory.resolve("agent-location.json"),
                      "{\"version\":\"1.0.0\",\"organizationId\":\"" + organizationId + "\"}");
    initRepository(directory);
    return directory;
  }

  /**
   * @return The scan root every Location is created under, which is also what a test's {@code startDirectory} points at.
   */
  protected Path locations() throws IOException {
    return Files.createDirectories(base.resolve("locations"));
  }

  /**
   * A briefing response entitling several Organizations, for the cases that need more than one Brief in one cycle.
   */
  protected String response(List<String> organizationIds, String... briefs) {
    String ids = organizationIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
    return "{\"organizationIds\":[" + ids + "],\"briefs\":[" + String.join(",", briefs) + "]}";
  }

  /**
   * A briefing response entitling exactly one Organization and carrying its Brief.
   */
  protected String response(String organizationId, int version, String... files) {
    return response(List.of(organizationId), briefJSON(organizationId, version, files));
  }

  /**
   * Root bypasses POSIX permission checks, so any test that simulates an unreadable directory has to refuse to run
   * rather than pass vacuously.
   */
  protected boolean runningAsRoot() throws IOException {
    try {
      Process process = new ProcessBuilder("id", "-u").start();
      String output;
      try (InputStream in = process.getInputStream()) {
        output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
      }

      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "id -u timed out");
      return "0".equals(output);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  protected String sha256(String content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  protected Path storeRoot() {
    return base.resolve("store");
  }
}

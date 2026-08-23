/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.cli;

import module java.base;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

// Disambiguates java.util.jar.Attributes from java.lang.classfile.Attributes, both pulled in by the module import

/**
 * The {@code version} subcommand: prints the version the build stamped into the jar's manifest.
 *
 * @author Brian Pontarelli
 */
public class Version {
  private final PrintStream out;

  public Version(PrintStream out) {
    this.out = out;
  }

  /**
   * Reads the version the build stamped into the jar's manifest. {@code Package.getImplementationVersion()} does not
   * work here — the JDK does not carry manifest attributes onto packages defined in a named module — so the manifest
   * is read straight out of this module.
   *
   * @return The jar's {@code Implementation-Version}, or {@code "dev"} when running from exploded classes, where
   *     there is no manifest to read.
   */
  private static String version() {
    try (InputStream is = Version.class.getModule().getResourceAsStream("META-INF/MANIFEST.MF")) {
      if (is == null) {
        return "dev";
      }

      String version = new Manifest(is).getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
      return version == null ? "dev" : version;
    } catch (IOException e) {
      return "dev";
    }
  }

  public int run() {
    out.println("handler " + version());
    return 0;
  }
}

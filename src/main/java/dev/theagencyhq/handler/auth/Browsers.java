/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Opens the developer's default web browser to a URL. The URL is printed first, so a machine where no browser can be
 * launched — over SSH, for example — still gives the developer something to work with.
 *
 * @author Brian Pontarelli
 */
public final class Browsers {
  private Browsers() {
  }

  public static void open(String url, PrintStream out) {
    out.println("Opening your browser to log in. If it does not open automatically, visit:");
    out.println(url);

    // Shell out rather than use java.awt.Desktop. On macOS, touching AWT turns this into a GUI application: it gets a
    // Dock icon and appears in the Cmd-Tab switcher. It would also drag java.desktop into a daemon's module graph.
    String[] command = browserCommand(url);
    if (command == null) {
      return;
    }

    try {
      new ProcessBuilder(command).start();
    } catch (IOException e) {
      // The printed URL above is the fallback
    }
  }

  private static String[] browserCommand(String url) {
    String os = System.getProperty("os.name").toLowerCase();
    if (os.contains("mac")) {
      return new String[]{"open", url};
    } else if (os.contains("nix") || os.contains("nux")) {
      return new String[]{"xdg-open", url};
    }

    return null;
  }
}

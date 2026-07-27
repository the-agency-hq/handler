/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;
import module org.lattejava.json;

/**
 * The Organization that published a Brief.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Organization(String id, String name) {
  public Organization {
    id = id == null ? "" : id.trim();
    name = name == null ? "" : name.trim();

    // The id is server-controlled and reaches storeRoot.resolve(). Path.resolve discards the base when given an
    // absolute argument, so an unvalidated id is an arbitrary-write primitive outside the store.
    if (!id.isEmpty() && (id.equals(".") || id.equals("..") || id.contains("/") || id.contains("\\")
                          || Path.of(id).isAbsolute() || Path.of(id).getNameCount() != 1)) {
      throw new IllegalArgumentException("Organization id is not a single path segment [" + id + "]");
    }
  }
}

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.location;

import module java.base;

/**
 * Mission Type filtering. Both lists arrive already trimmed and lowercased from their models' compact constructors, so
 * matching is case-insensitive by construction.
 *
 * @author Brian Pontarelli
 */
public final class MissionTypes {
  private MissionTypes() {
  }

  /**
   * @param fileTypes     The Mission Types the Brief file declares, or empty for "applies everywhere."
   * @param locationTypes The Mission Types the Location declares, or empty for "accepts everything."
   * @return True if the file belongs in the Location.
   */
  public static boolean includes(List<String> fileTypes, List<String> locationTypes) {
    return fileTypes.isEmpty() || locationTypes.isEmpty() || !Collections.disjoint(fileTypes, locationTypes);
  }
}

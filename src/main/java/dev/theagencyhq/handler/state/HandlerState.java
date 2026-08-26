/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.state;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.state.internal.HandlerStateJSON;

/**
 * The on-disk shape of {@code state.json}: what the last distribute cycle found and did. Written by the daemon after
 * every cycle and read by {@code handler status}, which never scans for Locations itself.
 *
 * @param lastRun   When the cycle finished.
 * @param locations Every Location the cycle found, in scan order.
 * @author Brian Pontarelli
 */
@JSON
public record HandlerState(Instant lastRun, List<LocationEntry> locations) {
  public HandlerState {
    locations = locations == null ? List.of() : List.copyOf(locations);
  }

  public static HandlerState fromJSON(byte[] json) {
    return HandlerStateJSON.fromJSON(json);
  }

  public String toPrettyString() {
    return HandlerStateJSON.toPrettyString(this);
  }
}

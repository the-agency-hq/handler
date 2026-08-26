/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module dev.theagencyhq.handler;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class StateStoreTest extends BaseTest {
  @Test
  public void absentFileLoadsEmptyRatherThanFailing() {
    // The daemon has simply not run yet
    assertTrue(new StateStore(base.resolve("missing/state.json")).load().isEmpty());
  }

  @Test
  public void malformedFileIsReportedRatherThanThrownRaw() throws IOException {
    Path file = base.resolve("state.json");
    Files.writeString(file, "{not json at all");

    StateStore.UnreadableStateException e = expectThrows(StateStore.UnreadableStateException.class,
                                                         () -> new StateStore(file).load());
    assertTrue(e.getMessage().contains(file.toString()), "Message was: " + e.getMessage());
  }

  @Test
  public void storeCreatesTheDirectoryAndRoundTrips() throws IOException {
    Path file = base.resolve("state/state.json");
    StateStore store = new StateStore(file);
    Instant lastRun = Instant.parse("2026-08-26T17:04:11Z");
    HandlerState state = new HandlerState(lastRun, List.of(
        new LocationEntry("/Users/dev/app", "42", List.of("code"), LocationStatus.SUCCESS, null),
        new LocationEntry("/Users/dev/other", "43", List.of(), LocationStatus.ERROR, "Skipped due to conflicts")));

    store.store(state);

    assertEquals(store.load().orElseThrow(), state);
    String json = Files.readString(file);
    assertTrue(json.contains("\"lastRun\": \"2026-08-26T17:04:11Z\""), "JSON was: " + json);
    assertTrue(json.contains("\"status\": \"ERROR\""), "JSON was: " + json);
    assertFalse(json.contains("\"message\": null"), "Nulls are omitted. JSON was: " + json);

    // No temp file is left beside it
    try (Stream<Path> files = Files.list(file.getParent())) {
      assertEquals(files.toList(), List.of(file));
    }
  }

  @Test
  public void storeReplacesThePreviousFile() {
    StateStore store = new StateStore(base.resolve("state.json"));
    store.store(new HandlerState(Instant.EPOCH, List.of(
        new LocationEntry("/one", "42", List.of(), LocationStatus.SUCCESS, null))));
    store.store(new HandlerState(Instant.EPOCH.plusSeconds(60), List.of()));

    HandlerState loaded = store.load().orElseThrow();
    assertEquals(loaded.lastRun(), Instant.EPOCH.plusSeconds(60));
    assertTrue(loaded.locations().isEmpty());
  }
}

/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.state;

import module java.base;

/**
 * Reads and writes {@code state.json}. Writes go through a sibling temp file that is then moved into place, so a
 * crash mid-write leaves the previous state intact rather than a truncated file.
 *
 * @author Brian Pontarelli
 */
public class StateStore {
  private final Path stateFile;

  public StateStore(Path stateFile) {
    this.stateFile = stateFile;
  }

  /**
   * @return The stored state, or empty when the daemon has not written one yet.
   * @throws UnreadableStateException When the file exists but cannot be read or parsed.
   */
  public Optional<HandlerState> load() {
    if (!Files.isRegularFile(stateFile)) {
      return Optional.empty();
    }

    try {
      return Optional.of(HandlerState.fromJSON(Files.readAllBytes(stateFile)));
    } catch (IOException e) {
      throw new UnreadableStateException("Unable to read the state file [" + stateFile + "]. Message was ["
          + e.getMessage() + "]", e);
    } catch (RuntimeException e) {
      throw new UnreadableStateException("The state file [" + stateFile + "] is malformed. Message was ["
          + e.getMessage() + "]", e);
    }
  }

  /**
   * @param state The state to write.
   * @throws UncheckedIOException When the file cannot be written. The previous file, if any, is left intact.
   */
  public void store(HandlerState state) {
    Path directory = stateFile.toAbsolutePath().getParent();
    Path temp = null;
    try {
      Files.createDirectories(directory);
      temp = Files.createTempFile(directory, "state", ".json");
      Files.writeString(temp, state.toPrettyString() + "\n");

      try {
        Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
      }
      temp = null;
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to write the state file [" + stateFile + "]", e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // Best-effort cleanup of the temp file; there is nothing actionable if it cannot be removed
        }
      }
    }
  }

  public static class UnreadableStateException extends RuntimeException {
    public UnreadableStateException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

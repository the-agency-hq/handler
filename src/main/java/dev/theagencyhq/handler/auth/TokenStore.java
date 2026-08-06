/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Reads and writes {@code tokens.json}. This file is machine-managed state, not configuration — it is rewritten
 * whenever a login or a refresh produces new tokens, so it deliberately does not share a file with the hand-edited
 * {@code handler.json}.
 *
 * <p>Writes go through a sibling temp file that is then moved into place atomically, so a crash mid-write leaves the
 * previous tokens intact rather than a truncated file that would force a re-login.
 *
 * @author Brian Pontarelli
 */
public class TokenStore {
  private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");

  private final Path tokensFile;

  public TokenStore(Path tokensFile) {
    this.tokensFile = tokensFile;
  }

  /**
   * Deletes the token file.
   *
   * @return Whether a token file was present and removed.
   */
  public boolean clear() {
    try {
      return Files.deleteIfExists(tokensFile);
    } catch (IOException e) {
      throw new AuthenticationException("Unable to remove the token file [" + tokensFile + "]. Message was ["
          + e.getMessage() + "]", e);
    }
  }

  /**
   * @return The stored tokens, or {@link Tokens#EMPTY} when the file is absent. Not being logged in is a normal state.
   */
  public Tokens load() {
    if (!Files.isRegularFile(tokensFile)) {
      return Tokens.EMPTY;
    }

    try {
      return Tokens.fromJSON(Files.readAllBytes(tokensFile));
    } catch (IOException e) {
      throw new AuthenticationException("Unable to read the token file [" + tokensFile + "]. Message was ["
          + e.getMessage() + "]", e);
    } catch (RuntimeException e) {
      throw new AuthenticationException("The token file [" + tokensFile + "] is malformed. Run [handler login] to"
          + " replace it. Message was [" + e.getMessage() + "]", e);
    }
  }

  public void store(Tokens tokens) {
    Path directory = tokensFile.toAbsolutePath().getParent();
    Path temp = null;
    try {
      Files.createDirectories(directory);
      temp = Files.createTempFile(directory, "tokens", ".json");
      Files.writeString(temp, tokens.toPrettyString() + "\n");
      Files.setPosixFilePermissions(temp, OWNER_READ_WRITE);

      try {
        Files.move(temp, tokensFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, tokensFile, StandardCopyOption.REPLACE_EXISTING);
      }
      temp = null;
    } catch (IOException e) {
      throw new AuthenticationException("Unable to write the token file [" + tokensFile + "]. Message was ["
          + e.getMessage() + "]", e);
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
}

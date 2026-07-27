/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;
import module org.lattejava.json;

import dev.theagencyhq.handler.brief.internal.BriefJSON;

/**
 * A published Brief. The {@code raw} component is populated by the parser with the verbatim JSON text of this object,
 * from its opening brace through its matching closing brace, so the Handler can store exactly what the Agency sent
 * without ever re-serializing it.
 *
 * @author Brian Pontarelli
 */
@JSON
public record Brief(@JSONRaw String raw, String checksum, Organization organization, int version,
                    List<BriefFile> files) {
  public Brief {
    // Not defaulted to "" — a zero-byte brief.json written from an empty raw would be silently corrupt, whereas a
    // null here is always a programming error and should fail loudly at construction
    Objects.requireNonNull(raw, "A Brief's raw wire text is required");
    checksum = checksum == null ? "" : checksum.trim();
    files = files == null ? List.of() : files;
  }

  public static Brief fromJSON(byte[] json) {
    return BriefJSON.fromJSON(json);
  }

  /**
   * @return The verbatim wire text as UTF-8 bytes. JSON source is required to be valid UTF-8, so this reproduces the
   *     bytes the Agency sent exactly.
   */
  public byte[] rawBytes() {
    return raw.getBytes(StandardCharsets.UTF_8);
  }
}

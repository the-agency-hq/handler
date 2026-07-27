/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.brief;

import module java.base;
import module org.lattejava.json;

/**
 * One file in a Brief. The Handler never interprets the content beyond decoding it to bytes.
 *
 * @author Brian Pontarelli
 */
@JSON
public record BriefFile(String path, String encoding, String mode, String content, String checksum,
                        List<String> missionTypes) {
  public static final String DEFAULT_ENCODING = "text";
  public static final String DEFAULT_MODE = "r--------";
  public static final String ENCODING_BASE64 = "base64";

  public BriefFile {
    path = path == null ? "" : path.trim();
    encoding = encoding == null || encoding.isBlank() ? DEFAULT_ENCODING : encoding.trim().toLowerCase(Locale.ROOT);
    mode = mode == null || mode.isBlank() ? DEFAULT_MODE : mode.trim();
    content = content == null ? "" : content;
    checksum = checksum == null ? "" : checksum.trim().toLowerCase(Locale.ROOT);
    missionTypes = missionTypes == null ? List.of() : missionTypes.stream().map(t -> t.trim().toLowerCase(Locale.ROOT)).toList();
  }

  /**
   * @return The content decoded to bytes according to {@link #encoding()}.
   * @throws IllegalArgumentException If the encoding is unknown or base64 content is not valid base64.
   */
  public byte[] decoded() {
    return switch (encoding) {
      case DEFAULT_ENCODING -> content.getBytes(StandardCharsets.UTF_8);
      case ENCODING_BASE64 -> Base64.getDecoder().decode(content);
      default -> throw new IllegalArgumentException("Unknown encoding [" + encoding + "] for file [" + path + "]");
    };
  }

  /**
   * @return The symbolic {@link #mode()} as a POSIX permission set.
   * @throws IllegalArgumentException If the mode is not nine {@code rwx-} characters in {@code ls -l} order.
   */
  public Set<PosixFilePermission> posixMode() {
    try {
      // The JDK owns the entire grammar: exactly nine characters, fixed alphabet, fixed positions. setuid, setgid, and
      // sticky are spelled s/S/t/T in this notation and are rejected here, because PosixFilePermission has no constant
      // for them and Files.setPosixFilePermissions could not apply them anyway. Failing loudly beats accepting a bit
      // that would be silently dropped.
      return PosixFilePermissions.fromString(mode);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Mode [" + mode + "] for file [" + path + "] is not a POSIX permission string such as [rw-r--r--]", e);
    }
  }
}

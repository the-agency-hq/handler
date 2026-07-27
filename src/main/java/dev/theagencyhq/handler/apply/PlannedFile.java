/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.apply;

import module java.base;

/**
 * One file the Handler intends to write into a Location, fully decoded and validated.
 *
 * @author Brian Pontarelli
 */
public record PlannedFile(Path relativePath, byte[] content, Set<PosixFilePermission> mode) {
}

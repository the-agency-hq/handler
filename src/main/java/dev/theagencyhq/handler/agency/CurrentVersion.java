/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module org.lattejava.json;

/**
 * One entry in the {@code currentVersions} array the Handler sends to The Agency. The checksum is opaque — it is
 * echoed back exactly as it was received and never computed locally.
 *
 * @author Brian Pontarelli
 */
@JSON
public record CurrentVersion(String organizationId, int version, String checksum) {
}

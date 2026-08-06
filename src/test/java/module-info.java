/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
module dev.theagencyhq.handler.tests {
  requires dev.theagencyhq.handler;
  requires java.logging;
  requires java.net.http;
  requires org.lattejava.http;
  requires org.lattejava.jwt;
  requires org.lattejava.version;
  requires org.testng;

  opens dev.theagencyhq.handler.tests to org.testng;
}

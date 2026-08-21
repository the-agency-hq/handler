/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
module dev.theagencyhq.handler {
  requires java.logging;
  requires java.net.http;
  requires org.lattejava.http;
  requires static org.lattejava.json;
  requires org.lattejava.jwt;
  requires org.lattejava.version;

  exports dev.theagencyhq.handler;
  exports dev.theagencyhq.handler.agency;
  exports dev.theagencyhq.handler.apply;
  exports dev.theagencyhq.handler.auth;
  exports dev.theagencyhq.handler.brief;
  exports dev.theagencyhq.handler.cli;
  exports dev.theagencyhq.handler.config;
  exports dev.theagencyhq.handler.location;
  exports dev.theagencyhq.handler.log;
  exports dev.theagencyhq.handler.tray;
}

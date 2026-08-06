/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler;

import module java.base;

import java.lang.System.Logger.*;

import dev.theagencyhq.handler.agency.AgencyClient;
import dev.theagencyhq.handler.apply.BriefPlanner;
import dev.theagencyhq.handler.apply.LocationApplier;
import dev.theagencyhq.handler.auth.AccessTokens;
import dev.theagencyhq.handler.auth.AuthConfiguration;
import dev.theagencyhq.handler.auth.Browsers;
import dev.theagencyhq.handler.auth.Credentials;
import dev.theagencyhq.handler.auth.Login;
import dev.theagencyhq.handler.auth.OAuthClient;
import dev.theagencyhq.handler.auth.OAuthTokenSupplier;
import dev.theagencyhq.handler.auth.TokenStore;
import dev.theagencyhq.handler.brief.BriefStore;
import dev.theagencyhq.handler.brief.FileBriefStore;
import dev.theagencyhq.handler.cli.HandlerCLI;
import dev.theagencyhq.handler.config.ConfigLoader;
import dev.theagencyhq.handler.config.HandlerConfig;
import dev.theagencyhq.handler.config.HandlerPaths;
import dev.theagencyhq.handler.location.LocationScanner;
import dev.theagencyhq.handler.log.Logging;

/**
 * The entry point. The only place that resolves paths from the environment and wires the object graph.
 *
 * @author Brian Pontarelli
 */
public final class Main {
  private static final System.Logger LOG = System.getLogger(Main.class.getName());

  void main(String... args) {
    HandlerPaths paths = HandlerPaths.fromEnvironment();
    Logging.configure(paths);

    HandlerConfig config;
    try {
      config = new ConfigLoader(paths, System::getenv).load();
    } catch (ConfigLoader.MalformedConfigException e) {
      // Guessing at intent here would silently sync from the wrong Agency
      System.err.println(e.getMessage());
      System.exit(2);
      return;
    }

    try {
      BriefStore store = new FileBriefStore(paths.storeRoot());
      LocationScanner scanner = new LocationScanner(config);
      BriefPlanner planner = new BriefPlanner();
      LocationApplier applier = new LocationApplier();
      DistributeThread distributeThread = new DistributeThread(config, store, scanner, planner, applier);
      TokenStore tokenStore = new TokenStore(paths.tokensFile());
      AuthConfiguration authConfiguration = new AuthConfiguration(config.authURL());
      OAuthClient oauthClient = new OAuthClient(authConfiguration);
      OAuthTokenSupplier tokens = new OAuthTokenSupplier(tokenStore, oauthClient);
      Login login = new Login(authConfiguration, oauthClient, tokenStore, Browsers::open);
      Credentials credentials = new Credentials(tokenStore, oauthClient, new AccessTokens(authConfiguration));
      AgencyClient agency = new AgencyClient(config.theAgencyURL(), tokens);
      Handler handler = new Handler(config, agency, store, distributeThread);
      Runtime.getRuntime().addShutdownHook(new Thread(handler::shutdown, "handler-shutdown"));

      HandlerCLI cli = new HandlerCLI(paths, config, store, scanner, planner, applier, handler, login, tokenStore,
                                      credentials, System.out);
      System.exit(cli.run(args));
    } catch (Exception e) {
      // Exiting 0 after this message would tell launchd and systemd the daemon shut down cleanly and needs no restart
      LOG.log(Level.ERROR, "Unable to start the Handler because a critical error was encountered. See the stack trace for details.", e);
      System.exit(1);
    }
  }
}

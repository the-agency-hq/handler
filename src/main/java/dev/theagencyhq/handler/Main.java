/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler;

import module java.base;
import module dev.theagencyhq.handler;
import java.lang.System.Logger.*;

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
      TrayFeed feed = new TrayFeed(paths.socketFile());
      DistributeThread distributeThread = new DistributeThread(config, store, scanner, planner, applier,
          feed::distributed);
      TokenStore tokenStore = new TokenStore(paths.tokensFile());
      AuthConfiguration authConfiguration = new AuthConfiguration(config.authURL());
      OAuthClient oauthClient = new OAuthClient(authConfiguration);
      OAuthTokenSupplier tokens = new OAuthTokenSupplier(tokenStore, oauthClient);
      Credentials credentials = new Credentials(tokenStore, oauthClient, new AccessTokens(authConfiguration));
      AgencyClient agency = new AgencyClient(config.theAgencyURL(), tokens);
      TokenWatcher watcher = new TokenWatcher(paths.tokensFile(), tokens::adoptFromDisk);
      Handler handler = new Handler(config, agency, store, distributeThread, feed, watcher);
      Runtime.getRuntime().addShutdownHook(new Thread(handler::shutdown, "handler-shutdown"));

      Path home = Path.of(System.getProperty("user.home"));
      boolean macOS = System.getProperty("os.name").toLowerCase().contains("mac");
      PrintStream out = System.out;
      OrganizationSelector selector = new OrganizationSelector(System.in, out, new UnixTerminal());

      Daemon daemon = new Daemon(handler, credentials, out);
      Start start = new Start(paths, home, macOS, ProcessCommand::execute, out);
      Stop stop = new Stop(paths, home, macOS, ProcessCommand::execute, out);
      Restart restart = new Restart(paths, home, macOS, ProcessCommand::execute, out);
      Sync sync = new Sync(handler);
      Status status = new Status(paths, config, store, scanner, planner, applier, tokenStore, credentials, out);
      Init init = new Init(agency, selector, Path.of("").toAbsolutePath(), System.in, out);
      InitSource initSource = new InitSource(Path.of("").toAbsolutePath(), out);
      Login login = new Login(authConfiguration, oauthClient, tokenStore, Browsers::open, out);
      Logout logout = new Logout(tokenStore, out);
      Uninstall uninstall = new Uninstall(paths, home, macOS, ProcessCommand::execute, System.in, out);
      HandlerCLI cli = new HandlerCLI(daemon, start, stop, restart, sync, status, init, initSource, login, logout,
          uninstall, new Help(out), new Version(out), out);
      System.exit(cli.run(args));
    } catch (Exception e) {
      // Exiting 0 after this message would tell launchd and systemd the daemon shut down cleanly and needs no restart
      LOG.log(Level.ERROR, "Unable to start the Handler because a critical error was encountered. See the stack trace for details.", e);
      System.exit(1);
    }
  }
}

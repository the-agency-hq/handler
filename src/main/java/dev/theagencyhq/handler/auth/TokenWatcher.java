/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.auth;

import module java.base;

/**
 * Watches {@code tokens.json} so a {@code handler login} or {@code handler logout} run from another process takes
 * effect in the daemon within seconds rather than at the next receive interval. Without this the tray can show "logged
 * out" for up to a full interval after a successful login.
 *
 * <p>A {@link WatchService} registered on the file's directory, filtered to the one file name, is the whole mechanism:
 * no socket, no signal, and no contract with the process doing the writing — anything that writes the file is seen.
 * {@code TokenStore} writes through a temp file and an atomic move, so an event for {@code tokens.json} always
 * describes a complete file.
 *
 * <p>The daemon writes the same file itself after every refresh, and that write must not wake the receive loop —
 * it would cost one pointless cycle per refresh. The {@code adopted} check is that filter: it re-reads the file and
 * answers whether the tokens actually changed from what the daemon already holds, so only another process's write turns
 * into a nudge. It also collapses the several events one write can raise into at most one nudge.
 *
 * <p>Best-effort throughout: a watcher that cannot start or stops unexpectedly is logged and the daemon carries on,
 * because the receive loop re-reads the tokens on every 401 anyway. The nudge is never a correctness requirement.
 *
 * @author Brian Pontarelli
 */
public class TokenWatcher {
  private static final System.Logger LOG = System.getLogger(TokenWatcher.class.getName());

  private final BooleanSupplier changedOnDisk;
  private final Path tokensFile;
  private volatile WatchService service;

  /**
   * @param tokensFile    The token file to watch.
   * @param changedOnDisk Re-reads the file and answers whether the tokens changed from what this process holds. Invoked
   *                      on the watcher thread after every event for the file.
   */
  public TokenWatcher(Path tokensFile, BooleanSupplier changedOnDisk) {
    this.tokensFile = tokensFile.toAbsolutePath();
    this.changedOnDisk = changedOnDisk;
  }

  /**
   * Starts watching. The directory is created when it does not exist yet — a fresh install has no {@code tokens.json}
   * until the first login, and a watch needs a directory to register on.
   *
   * @param onChange Run once for every change {@code adopted} confirms.
   */
  public void start(Runnable onChange) {
    Path directory = tokensFile.getParent();
    try {
      Files.createDirectories(directory);
      service = directory.getFileSystem().newWatchService();
      register(directory);
      Thread.ofVirtual().name("handler-token-watcher").start(() -> watch(directory, onChange));
    } catch (Throwable t) {
      LOG.log(System.Logger.Level.WARNING, "Unable to watch [" + tokensFile + "]. A login will be adopted at the next"
          + " receive cycle instead.", t);
      stop();
    }
  }

  /**
   * Stops watching. A no-op when the watcher never started.
   */
  public void stop() {
    WatchService local = service;
    service = null;
    if (local != null) {
      try {
        local.close();
      } catch (IOException e) {
        // Closing is best-effort; the process is exiting
      }
    }
  }

  private void changed(Runnable onChange) {
    try {
      if (changedOnDisk.getAsBoolean()) {
        LOG.log(System.Logger.Level.INFO, "The stored tokens changed on disk. Waking the receive loop.");
        onChange.run();
      }
    } catch (Throwable t) {
      // The loop must survive a failing callback the same way IntervalThread survives a failing cycle
      LOG.log(System.Logger.Level.WARNING, "Unable to handle a change to [" + tokensFile + "]", t);
    }
  }

  private void register(Path directory) throws IOException {
    directory.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_DELETE);
  }

  @SuppressWarnings("InfiniteLoopStatement")
  private void watch(Path directory, Runnable onChange) {
    WatchService local = service;
    try {
      while (true) {
        WatchKey key = local.take();

        // An overflow means events were dropped, and one of them may have been ours. Checking the file is cheap, and
        // the adopted filter turns a false alarm into nothing.
        boolean relevant = false;
        for (WatchEvent<?> event : key.pollEvents()) {
          if (event.kind() == StandardWatchEventKinds.OVERFLOW || tokensFile.getFileName().equals(event.context())) {
            relevant = true;
          }
        }

        if (!key.reset()) {
          // The directory itself was removed. Put it back and watch again — the next login would recreate it anyway,
          // and without a registration that login would go unnoticed
          Files.createDirectories(directory);
          register(directory);
          relevant = true;
        }

        if (relevant) {
          changed(onChange);
        }
      }
    } catch (ClosedWatchServiceException e) {
      // stop() doing its job
    } catch (Throwable t) {
      if (service != null) {
        LOG.log(System.Logger.Level.WARNING, "Stopped watching [" + tokensFile + "]. A login will be adopted at the"
            + " next receive cycle instead.", t);
      }
    }
  }
}

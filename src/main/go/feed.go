// Copyright (c) 2026 The Agency HQ
// SPDX-License-Identifier: MIT

package main

import (
	"bufio"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// retryInterval is how long the tray waits before redialing a daemon that is not answering.
const retryInterval = 5 * time.Second

// message is one line of the daemon's tray feed: newline-delimited JSON over the Unix domain socket served by the
// Java TrayFeed. A status line carries the full current state; a notification line carries only Message.
type message struct {
	Type         string   `json:"type"`
	State        string   `json:"state"`
	LastResponse *int64   `json:"lastResponse"`
	LastRun      *int64   `json:"lastRun"`
	Locations    *int     `json:"locations"`
	LastChanges  *changes `json:"lastChanges"`
	Message      string   `json:"message"`
}

// changes is the last change set the Handler applied, with At in epoch milliseconds.
type changes struct {
	Applied  int   `json:"applied"`
	Conflict int   `json:"conflict"`
	Failed   int   `json:"failed"`
	At       int64 `json:"at"`
}

// String renders the change counts the same way the AWT tray does: "2 applied, 1 conflict, 3 failed".
func (c *changes) String() string {
	var parts []string
	if c.Applied > 0 {
		parts = append(parts, plural(c.Applied, "applied", "applied"))
	}
	if c.Conflict > 0 {
		parts = append(parts, plural(c.Conflict, "conflict", "conflicts"))
	}
	if c.Failed > 0 {
		parts = append(parts, plural(c.Failed, "failed", "failed"))
	}
	return strings.Join(parts, ", ")
}

// feed reads the daemon's status socket forever, redialing whenever the daemon is down. All callbacks run on the
// feed's own goroutine, one at a time.
type feed struct {
	onDisconnect   func()
	onNotification func(string)
	onStatus       func(message)
}

// run dials the socket and pumps messages until the process exits. Call it on its own goroutine.
func (f *feed) run(path string) {
	for {
		conn, err := net.Dial("unix", path)
		if err != nil {
			f.onDisconnect()
			time.Sleep(retryInterval)
			continue
		}

		scanner := bufio.NewScanner(conn)
		scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
		for scanner.Scan() {
			var m message
			if err := json.Unmarshal(scanner.Bytes(), &m); err != nil {
				continue // a malformed line must never kill the connection
			}

			switch m.Type {
			case "status":
				f.onStatus(m)
			case "notification":
				f.onNotification(m.Message)
			}
		}

		conn.Close()
		f.onDisconnect()
		time.Sleep(retryInterval)
	}
}

// socketPath resolves the daemon's socket exactly the way the Java HandlerPaths resolves its state directory:
// XDG_STATE_HOME when it is set to an absolute path, otherwise ~/.local/state, then the-agency-hq/handler.sock.
func socketPath() string {
	state := strings.TrimSpace(os.Getenv("XDG_STATE_HOME"))
	if state == "" || !filepath.IsAbs(state) {
		home, err := os.UserHomeDir()
		if err != nil {
			home = "."
		}
		state = filepath.Join(home, ".local", "state")
	}
	return filepath.Join(state, "the-agency-hq", "handler.sock")
}

func plural(count int, singular, pluralForm string) string {
	if count == 1 {
		return "1 " + singular
	}
	return strconv.Itoa(count) + " " + pluralForm
}

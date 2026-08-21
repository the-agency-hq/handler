// Copyright (c) 2026 The Agency HQ
// SPDX-License-Identifier: MIT

package main

// trayState mirrors the Java TrayState enum, plus the one state only an external process can be in: the daemon
// itself is not running, so there is no status to report.
type trayState int

const (
	stateDisconnected trayState = iota
	stateHealthy
	stateLoggedOut
	stateUnreachable
)

// parseState maps a feed state name onto a trayState. An unknown name is treated as healthy rather than dropped,
// because a daemon that is answering the feed is by definition running.
func parseState(name string) trayState {
	switch name {
	case "LOGGED_OUT":
		return stateLoggedOut
	case "UNREACHABLE":
		return stateUnreachable
	default:
		return stateHealthy
	}
}

// tooltip returns the hover text for a state. The three daemon states match the AWT tray verbatim.
func (s trayState) tooltip() string {
	switch s {
	case stateHealthy:
		return "The Agency Handler — syncing with The Agency HQ"
	case stateLoggedOut:
		return "The Agency Handler — not logged in. Run [handler login]."
	case stateUnreachable:
		return "The Agency Handler — The Agency HQ is unreachable"
	default:
		return "The Agency Handler — the Handler daemon is not running"
	}
}

// label returns the status line shown at the top of the menu. macOS barely surfaces tray tooltips, so the menu
// carries the same information the tooltip does.
func (s trayState) label() string {
	switch s {
	case stateHealthy:
		return "Syncing with The Agency HQ"
	case stateLoggedOut:
		return "Not logged in — run [handler login]"
	case stateUnreachable:
		return "The Agency HQ is unreachable"
	default:
		return "The Handler daemon is not running"
	}
}

// Copyright (c) 2026 The Agency HQ
// SPDX-License-Identifier: MIT

// The GoGPU system tray companion for the Handler daemon — the daemon's only UI; the JVM itself stays headless. It
// shows the brace logo with a status glyph, a menu of the last cycle's details, and a notification whenever The
// Agency sent new Briefs or the Handler changed this machine. State arrives over the daemon's Unix domain socket
// feed; when the daemon is not running the icon shows the empty braces and the tray redials until it comes back.
package main

import (
	"image/color"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/gogpu/systray"
)

// timestampFormat renders feed timestamps in Java's DateTimeFormatter MEDIUM style, in the system timezone; the
// feed sends epoch milliseconds and leaves formatting to this process.
const timestampFormat = "Jan 2, 2006, 3:04:05 PM"

func main() {
	// The app name is what Linux notification banners show as the sender; macOS and Windows attribute notifications
	// by app identity, so it is a no-op there
	tray := systray.New().SetAppName("The Agency HQ")

	menu := systray.NewMenu()
	status := menu.Add(stateDisconnected.label(), func() {})
	lastResponse := menu.Add("Last response: never", func() {})
	locations := menu.Add("Locations: unknown", func() {})
	lastRun := menu.Add("Last run: never", func() {})
	lastChanges := menu.Add("Last changes: none yet", func() {})
	for _, item := range []*systray.MenuItem{status, lastResponse, locations, lastRun, lastChanges} {
		item.SetDisabled(true)
	}

	// This process is standalone with no other clean way to stop it, so the menu carries its own quit item
	menu.AddSeparator()
	menu.Add("Quit", func() {
		tray.Remove()
		os.Exit(0)
	})

	// Pre-render every state in both polarities: black for light surfaces, white for dark ones. On macOS only the
	// alpha channel matters — the template machinery recolors the icon to match the menu bar.
	states := []trayState{stateDisconnected, stateHealthy, stateLoggedOut, stateUnreachable}
	black := map[trayState][]byte{}
	white := map[trayState][]byte{}
	for _, s := range states {
		black[s] = renderIcon(s, 44, color.Black)
		white[s] = renderIcon(s, 44, color.White)
	}

	// State changes are rare, so the icon swap from the feed goroutine is tolerable even though only menu item
	// updates are dispatched to the platform thread by the library
	current := trayState(-1)
	apply := func(next trayState) {
		if next == current {
			return
		}
		current = next

		switch runtime.GOOS {
		case "darwin":
			tray.SetTemplateIcon(black[next])
		default:
			// Linux status areas are overwhelmingly dark panels
			tray.SetIcon(white[next])
		}
		tray.SetTooltip(next.tooltip())
		status.SetLabel(next.label())
	}

	f := &feed{
		onDisconnect: func() {
			apply(stateDisconnected)
		},
		onNotification: func(text string) {
			notify(tray, text)
		},
		onStatus: func(m message) {
			apply(parseState(m.State))
			lastResponse.SetLabel("Last response: " + formatTimestamp(m.LastResponse))
			if m.Locations == nil {
				locations.SetLabel("Locations: unknown")
			} else {
				locations.SetLabel("Locations: " + strconv.Itoa(*m.Locations))
			}
			lastRun.SetLabel("Last run: " + formatTimestamp(m.LastRun))
			if m.LastChanges == nil {
				lastChanges.SetLabel("Last changes: none yet")
			} else {
				at := time.UnixMilli(m.LastChanges.At).Format(timestampFormat)
				lastChanges.SetLabel("Last changes: " + m.LastChanges.String() + " — " + at)
			}
		},
	}

	apply(stateDisconnected)
	tray.SetMenu(menu).Show()
	go f.run(socketPath())

	// Fires one notification shortly after startup so attribution and the icon can be checked without waiting for
	// The Agency to send something
	if len(os.Args) > 1 && os.Args[1] == "--test-notification" {
		go func() {
			time.Sleep(1500 * time.Millisecond)
			notify(tray, "Notifications are working")
		}()
	}

	if err := tray.Run(); err != nil {
		os.Exit(1)
	}
}

func formatTimestamp(millis *int64) string {
	if millis == nil {
		return "never"
	}
	return time.UnixMilli(*millis).Format(timestampFormat)
}

// notify raises one OS notification. macOS attributes a notification to the app bundle that posts it, so when this
// process runs from The Agency Handler.app — what `latte tray` assembles — the native path shows the Handler's name
// and icon. A bare binary never registers with Notification Center, so there osascript posts it instead, attributed
// to Script Editor. On Linux the library's D-Bus call carries the app name set at startup.
func notify(tray *systray.SystemTray, text string) {
	if runtime.GOOS == "darwin" && !bundled() {
		script := "display notification \"" + escapeAppleScript(text) + "\" with title \"The Agency Handler\""
		_ = exec.Command("osascript", "-e", script).Start()
		return
	}
	tray.ShowNotification("The Agency Handler", text)
}

// bundled reports whether this process is running from inside a macOS app bundle.
func bundled() bool {
	executable, err := os.Executable()
	return err == nil && strings.Contains(executable, ".app/Contents/MacOS/")
}

func escapeAppleScript(text string) string {
	return strings.ReplaceAll(strings.ReplaceAll(text, `\`, `\\`), `"`, `\"`)
}

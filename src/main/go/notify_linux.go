// Copyright (c) 2026 The Agency HQ
// SPDX-License-Identifier: MIT

package main

import (
	"github.com/godbus/dbus/v5"
	"github.com/gogpu/systray"
)

// appName is what Linux notification banners show as the sender.
const appName = "The Agency HQ"

// appIcon names the icon install.sh places in the hicolor theme; a desktop without it shows no icon, as before.
const appIcon = "the-agency-hq-handler"

// showNotification posts straight to org.freedesktop.Notifications because the systray library hardcodes its own
// name ("gogpu-systray") as the app_name, which is what the desktop shows as the sender. Falls back to the library
// when the session bus is unavailable.
func showNotification(tray *systray.SystemTray, title, text string) {
	conn, err := dbus.SessionBus()
	if err != nil {
		tray.ShowNotification(title, text)
		return
	}

	obj := conn.Object("org.freedesktop.Notifications", "/org/freedesktop/Notifications")
	call := obj.Call("org.freedesktop.Notifications.Notify", 0,
		appName,                   // app_name
		uint32(0),                 // replaces_id
		appIcon,                   // app_icon
		title,                     // summary
		text,                      // body
		[]string{},                // actions
		map[string]dbus.Variant{}, // hints
		int32(-1),                 // expire_timeout (-1 = server default)
	)
	if call.Err != nil {
		tray.ShowNotification(title, text)
	}
}

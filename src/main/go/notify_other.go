// Copyright (c) 2026 The Agency HQ
// SPDX-License-Identifier: MIT

//go:build !linux

package main

import "github.com/gogpu/systray"

// showNotification posts through the systray library, which attributes the notification natively on this platform.
func showNotification(tray *systray.SystemTray, title, text string) {
	tray.ShowNotification(title, text)
}

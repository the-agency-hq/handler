package main

import (
	"image/color"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

// TestDumpIcons is a scratch visual check, not a real test. It writes each state's icon at menu bar and preview
// sizes so a human can look at them.
func TestDumpIcons(t *testing.T) {
	dir := os.Getenv("ICON_DUMP_DIR")
	if dir == "" {
		t.Skip("ICON_DUMP_DIR not set")
	}

	names := map[trayState]string{
		stateDisconnected: "disconnected",
		stateHealthy:      "healthy",
		stateLoggedOut:    "logged-out",
		stateUnreachable:  "unreachable",
	}
	for state, name := range names {
		for _, size := range []int{44, 176} {
			data := renderIcon(state, size, color.White)
			if data == nil {
				t.Fatalf("renderIcon returned nil for %s at %d", name, size)
			}
			path := filepath.Join(dir, name+"-"+strconv.Itoa(size)+".png")
			if err := os.WriteFile(path, data, 0o644); err != nil {
				t.Fatal(err)
			}
		}
	}
}

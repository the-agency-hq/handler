# Status state file

Status: proposed

Amends §10 of `2026-07-26-handler-core-sync-design.md`, which said `handler status` recomputes everything from disk
and there is no state file. That made `status` scan the whole start directory on every run. This design replaces the
scan with a state file the daemon writes and the CLI reads.

## 1. The state file

| Item     | Value                                                                                       |
|----------|---------------------------------------------------------------------------------------------|
| Path     | `$XDG_STATE_HOME/the-agency-hq/state.json` (default `~/.local/state`), beside `handler.log` |
| Writer   | `DistributeThread.distribute()`, at the end of every distribute cycle                        |
| Readers  | `handler status`                                                                            |
| Format   | JSON, written to a temp file then moved into place so a crash never leaves a half-written file |
| Removal  | `handler uninstall` deletes it                                                              |

Every distribute cycle writes the file, whether it ran inside the daemon or from `handler sync`. A failure to write
the file is logged and never stops syncing.

```json
{
  "lastRun": "2026-08-26T17:04:11Z",
  "locations": [
    {
      "root": "/Users/dev/app",
      "organizationId": "42",
      "missionTypes": ["code"],
      "status": "SUCCESS"
    },
    {
      "root": "/Users/dev/other",
      "organizationId": "43",
      "missionTypes": [],
      "status": "ERROR",
      "message": "Location has unmanaged files at planned paths and was skipped"
    }
  ]
}
```

| Field            | Meaning                                                                                           |
|------------------|---------------------------------------------------------------------------------------------------|
| `lastRun`        | When the cycle finished, ISO-8601 UTC                                                             |
| `locations`      | Every Location the scan found, in scan order                                                      |
| `root`           | The Location directory                                                                            |
| `organizationId` | From the Location's `agent-location.json`                                                         |
| `missionTypes`   | From the marker; empty means all                                                                  |
| `status`         | `SUCCESS` when the Location was applied, unchanged, or had no Brief to apply; `ERROR` otherwise   |
| `message`        | Why it is `ERROR`; absent on `SUCCESS`                                                            |

`status` and `message` record what the last cycle did. `handler status` does not print them (see §2) but the tray
or a later command can.

## 2. `handler status`

`status` never scans the start directory. It reads the state file and, for each Location in it, works out what the
next distribute cycle will do using the existing read-only `LocationApplier.inspect()`. That is a check of one
Location against its planned files, not a directory walk, and it writes nothing.

The last cycle's `status` is not shown because it can be out of date: a conflict the developer has since fixed will
apply cleanly next time, and that is what the developer wants to know.

### Output

```
Config file:     /Users/dev/.config/the-agency-hq/handler.json
Tokens file:     /Users/dev/.config/the-agency-hq/tokens.json
Store root:      /Users/dev/.local/share/the-agency-hq/briefs
Log file:        /Users/dev/.local/state/the-agency-hq/handler.log
State file:      /Users/dev/.local/state/the-agency-hq/state.json
The Agency URL:  https://theagencyhq.dev
Auth URL:        https://auth.theagencyhq.dev
Access token:    present
Introspect:      valid — dev@example.com, expires 2026-08-27T17:04:11Z

Organizations
  42  version=3

Locations (last daemon run 2026-08-26 10:04:11)
  /Users/dev/app
    Mission types: code
    Status:        Up-to-date
  /Users/dev/other
    Mission types: all
    Status:        Skipped due to conflicts
```

The Organizations block is unchanged. When there is no state file, the Locations block reads:

```
Locations
  Unknown. This status output will update the next time the daemon runs.
```

A state file that cannot be parsed is reported the same way, with the parse error, and is rewritten by the next
cycle.

### Location status values

| Shown                                 | When                                                                              |
|---------------------------------------|-----------------------------------------------------------------------------------|
| `Up-to-date`                          | The Location already matches its Brief; the next cycle changes nothing            |
| `Pending new version`                 | The next cycle will write files                                                   |
| `Pending removal`                     | The Organization was revoked; the next cycle tears the Location down              |
| `Skipped due to conflicts`            | Unmanaged files sit at planned paths; the next cycle skips it until `sync --force` |
| `No Brief`                            | The store holds no Brief for the Organization; the next cycle skips it            |
| `Invalid Brief: <reason>`             | The Brief cannot be planned; the next cycle fails it                              |
| `Unreadable`                          | The Location cannot be read; the next cycle fails it                              |
| `Removed`                             | `agent-location.json` is gone; the next cycle will not find it                    |

## 3. Code changes

| Change                                                       | Where                                        |
|--------------------------------------------------------------|----------------------------------------------|
| New `state` package: `HandlerState`, `LocationEntry`, `LocationStatus`, `StateStore` | `dev.theagencyhq.handler.state` |
| `stateFile()` derived from the log file's directory          | `HandlerPaths`                               |
| Takes a `StateStore`; records each Location's result and writes the file after the loop | `DistributeThread` |
| Takes a `StateStore` instead of a `LocationScanner`; new output | `Status`                                  |
| Deletes the state file                                       | `Uninstall`                                  |
| Wires `StateStore`                                           | `Main`                                       |
| Updated `status` line                                        | `Help`                                       |

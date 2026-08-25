# Handler

The Handler is The Agency HQ daemon that runs on a developer's machine. It is the conduit between The Agency and
the Agents working at Locations on that machine. It does two things on independent schedules:

1. **Receive** — ask The Agency for new Briefs and store them locally, immutably.
2. **Distribute** — find every Location on the machine and make the files in that Location match the Brief its
   Organization published.

The Handler never translates, decodes, or interprets Brief content beyond what is needed to write bytes to disk.
It is a courier, not an author.

## Download

Download the latest version of the Handler using the releases of this project here: https://github.com/the-agency-hq/handler/releases

## How it works

- **Briefs** are stored per Organization and per version under the Handler's local store, exactly as they arrived from The Agency. They are never modified or pruned.
- **Locations** are discovered by scanning configured roots for an `agent-location.json` marker file. A Location owns its whole subtree, which means Locations cannot be nested.
- **Applying** a Brief to a Location is manifest-driven: a `.handler-manifest` records what the Handler wrote, so updates and teardowns only ever touch files the Handler owns. Unmanaged files are never destroyed. Conflicts are logged and the Location is skipped until a human resolves them. All Handler-written paths are excluded from Git via `.git/info/exclude` - the Handler never touches `.gitignore`.
- **Authentication** uses OAuth to connect to The Agency. This leverages [FusionAuth](https://fusionauth.io) as the identity provider. `handler login` runs a browser-based flow and stores tokens locally.

## CLI

The `handler` binary is a single executable with subcommands:

| Command                  | Purpose                                                            |
|--------------------------|--------------------------------------------------------------------|
| `handler daemon`         | Run the receive/distribute loop in the foreground.                 |
| `handler start`          | Start the installed daemon through launchd or systemd.             |
| `handler stop`           | Stop the daemon until `handler start` or the next login.           |
| `handler restart`        | Restart the daemon, adopting configuration changes.                |
| `handler sync [--force]` | Run one receive + distribute pass; `--force` adopts conflicts.     |
| `handler status`         | Show the state of every Organization and Location on this machine. |
| `handler init`           | Mark the current directory as a Location for an Organization.      |
| `handler init-source`    | Scaffold a Brief Source repository in the current directory.       |
| `handler login`          | Authenticate with The Agency.                                      |
| `handler logout`         | Discard stored tokens.                                             |
| `handler uninstall`      | Remove the Handler and its local state.                            |

Configuration lives in `~/.config/the-agency-hq/handler.json` (XDG-aware). Logs go to stderr and a rotating file
under `$XDG_STATE_HOME/the-agency-hq/handler.log`.
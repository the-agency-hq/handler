# Handler

## Local FusionAuth

`handler login` authenticates against FusionAuth. The tests in `LoginTest` run the real flow against a local
instance, so they need one running:

    cd src/test/fusionauth && docker compose up -d

It comes up on `http://localhost:9015` with the Handler Application, an admin (`admin@theagencyhq.org` /
`password`), and a test user (`agent@theagencyhq.org` / `password`) already provisioned by Kickstart. Kickstart
only runs against an empty database, so re-provisioning after changing `kickstart.json` needs
`docker compose down -v` first.

Point the Handler at it by setting `authURL` in `handler.json`:

    "authURL": "http://localhost:9015"

The scheme is required. A value that is not an absolute `http` or `https` URL — `localhost:9015` — is logged as a
warning and the default issuer is used instead, so a typo cannot keep the daemon or `handler status` from running.
`handler login` will go to the default issuer until the value is fixed.

## Todo

* [ ] Figure out how to handle mono-repositories
* [ ] Figure out how to connect to a localhost Agency securely

# Client

This is the official client for [MediaGarrd](https://github.com/MediaGarrd/MediaGarrd).

## Requirements
- Docker with Compose v2 (`docker compose ...`)
- A running [MediaGarrd Server](https://github.com/MediaGarrd/Server) that has finished its first-time setup

## Installation
```bash
git clone --depth 1 https://github.com/MediaGarrd/Client.git
cd Client
cp docker-compose.example.yml docker-compose.yml
docker compose up --build -d
```

Then open `http://<client-host>:8081`.

The defaults in `docker-compose.yml` work as-is. To change them before the first start:
- **Port:** change both `CLIENT_PORT` and the `ports` mapping (default `8081`).
- **Where downloaded backups are stored on the host:** change the left side of the `./data/client/downloads` volume.
- **Where the client keeps its state on the host:** change the left side of the `./data/client/state` volume. This holds the selected server, known servers, and pickup interval, so keep it on persistent storage.
- **Server port to look for:** `MEDIAGARRD_SERVER_PORT` (default `38471`, the server's default). Discovery and "Connect by IP" try this port first.
- **Connect to a server automatically:** add `MEDIAGARRD_SERVER_IP=<server-ip>` (or `<server-ip>:<port>`) to `environment`. Until a server is selected, the client connects to this address as soon as it answers. Without it, pick a server in the web page.

`CLIENT_BACKUP_RUN_TIMEOUT` (ISO-8601, default `PT6H`, minimum `PT1M`; the client refuses to start below that) is how long "Run Backup Now" waits for the server to finish a backup. If it takes longer, the client answers 504 and the page shows an error; the backup keeps running on the server and the dashboard still shows when it finishes.

`CLIENT_PICKUP_INTERVAL` (ISO-8601, default `PT12H`) only sets the initial value. Once the interval has been saved in the web page, the stored value is used.

## Usage
- **Dashboard** connects to a MediaGarrd server by IP, from the list of known servers, or by scanning the local network. It shows the server's status, runs a backup, downloads backups, and sets how often the client downloads the latest backup on its own.
- **Server Config** changes the connected server's backup location, backup interval, and number of backups to keep, and adds, edits, and removes the services it backs up. The server must have finished its first-time setup (the `/config` page on the server) before it accepts changes from the client.

- **Live status and refresh**: the client keeps the dashboard state (active and known servers, link state, run state, the server's backup interval and retention, and both versions) and pushes every change to each open page through a live connection (see Live events). The Server Status "State" and the backup buttons change to Running the moment a run starts and back to Idle the moment it ends; the Backup Archives table reloads when a run finishes, when the server announces an archive change, when the connected server changes, and when the link comes back. The page does not poll the server. It re-reads the client's stored state once a minute and when a hidden tab becomes visible or the Dashboard is shown again, as a catch-all; the Backup Archives table is also reloaded then, but only when a server is connected and its link is up. A normal end of the live connection (it is renewed every few minutes) does not show "Client unreachable"; that appears only if the client stays unreachable for 8 seconds. The Pickup Interval field is only filled when the page loads and is never changed by pushes or refreshes.
- **Versions** panel on the Dashboard shows the client's and the connected server's version. Each checks GitHub for the latest release once a day. A row says "Up-to-date" when it is current, turns into a red box with a link to the latest release when an update is available, and is muted when the check could not be made (offline, rate limited) or no server is connected. The server needs to be a version that provides `GET /api/v1/version`.

Every setting is stored in the `./data/client/state` volume and survives restarts and container rebuilds.

### Finding servers
"Scan Local Network" checks every address of the networks the client is attached to, on `MEDIAGARRD_SERVER_PORT`, `38471`, `8080`, and `80`, and lists every MediaGarrd server that answers. "Connect by IP" also accepts `<ip>:<port>`, and scans every port of the host when the server is not on one of those ports.

With the default Docker networking the client only sees the container network, so a scan finds servers running on the same host but not on other machines. Use "Connect by IP" or `MEDIAGARRD_SERVER_IP` for those, or add `network_mode: host` to the service (and remove its `ports` mapping) so the scan covers your LAN.

## Update alerts
The Versions panel is driven by the `versions` event (`{"client": {...}, "server": {...} | null}`) and the `versions` field of `GET /api/client/dashboard`. Each status has `currentVersion`, `latestVersion`, `releaseUrl`, `updateAvailable`, `checkState` (`OK`, `NO_RELEASES`, `ERROR`, `NOT_CHECKED`, or `UNAVAILABLE` when the server cannot be asked), `checkedAt`, and `message`. `server` is `null` while no server is selected.

To change what is checked, set `MEDIAGARRD_UPDATE_REPOSITORY` (default `MediaGarrd/Client`), `MEDIAGARRD_GITHUB_API_URL` (default `https://api.github.com`), or `MEDIAGARRD_UPDATE_CHECK_INTERVAL` (ISO-8601, default `PT24H`, minimum `PT1H`) in `environment`.

## Live events
`GET /api/client/events` is a Server-Sent Events stream for the web page, and `GET /api/client/dashboard` returns the same state as one JSON snapshot without contacting the server. The client keeps one connection to the connected server's `GET /api/v1/events`, owns the dashboard state, and pushes it. Every new subscriber first gets one `snapshot`, followed by granular events:
- `snapshot` `{"activeServer": "<url or null>", "knownServers": [...], "link": "LIVE|DEGRADED|CONNECTING|DOWN", "running": bool or null, "health": {"backupInterval": "PT6H", "backupRetentionCount": 5} or null, "versions": {"client": {...}, "server": {...} or null}}`: the whole state. Also sent to everyone when the active server changes.
- `link` `{"server": "<url or empty>", "state": "LIVE|DEGRADED|CONNECTING|DOWN"}`.
  - `LIVE`: the server's event stream is open, and run state is pushed.
  - `DEGRADED`: no event stream (an older server, the server at its subscriber cap, or a reconnect backoff), but the server answers `GET /api/v1/health`. The client polls it every 8 seconds, once per client no matter how many pages are open, and synthesizes `state` events from it. The poll stops when the link is `LIVE`.
  - `CONNECTING`: a server was just selected, or its stream just dropped, and the first health check has not answered yet. Run state is unknown.
  - `DOWN`: no active server, or no event stream and the last health check failed. Run state is unknown (`running` is `null`) and the client keeps checking.
- `state` `{"running": bool, "at": "<ISO-8601>"}`, `run-started` `{"running": true, "startedAt": "..."}` and `run-finished` `{"running": false, "status": "COMPLETED|PARTIAL|FAILED", "startedAt": "...", "finishedAt": "..."}`: forwarded from the server.
- `backups-changed` `{"at": "<ISO-8601>"}`: forwarded from the server when its archive list changed outside a run (an archive was deleted or the backup location changed). While the link is `DEGRADED` the client lists the server's archives once a minute and sends this itself when the list changed, so open pages never ask the server.
- `config` `{"backupInterval": "PT6H", "backupRetentionCount": 5}`: the server's schedule or retention. The client refetches the server's health when the server announces `config-changed`, on link up, and once a minute while the link is `LIVE`, and pushes this only when the values changed.
- `servers` `{"knownServers": [...], "activeServer": "<url or null>"}`: the client's list of known servers changed (discovery, a scan, a connect).
- `versions` `{"client": {...}, "server": {...} or null}`: the client's update check result changed, or the server's version was fetched. The server's version is fetched when the link comes up (unless it was fetched successfully in the last 5 minutes and the server was not down in between) or the server changes, and at most hourly after that. A version the server has not checked yet is asked for again after 30 seconds, an error after 5 minutes, and an unavailable one after 10 minutes.
- `ping` `{}` every 20 seconds.

The first event on each stream carries `retry: 5000`, so a browser that loses the client waits 5 seconds before reconnecting. The client reconnects to the server with a growing delay (1 to 30 seconds), resubscribes when the connected server changes, and drops a stream that has been silent for 45 seconds. A server that answers 404 (older than this feature) is tried again every 5 minutes and is `DEGRADED` meanwhile. At most 32 browsers can subscribe at once (further requests get an empty 503). If a reverse proxy sits in front, the response sends `Cache-Control: no-cache` and `X-Accel-Buffering: no`; do not buffer it.

## Tests
`make test` runs the Java tests (`./gradlew test`) and the browser tests. The browser tests need Node 20.19 or newer, have no dependencies, and can be run on their own with `node --test src/test/js/*.test.mjs`. They cover the pure modules in `src/main/resources/static/js`: `dashboard-model.js` (event reduction and what triggers a reload), `view-model.js` (link state, known servers and versions rendering decisions) and `reachability.js` (when the client counts as unreachable).

## Updating
```bash
git pull
docker compose up --build -d
```
Downloaded backups and the client state live in the mounted `./data/client` volumes and are kept.

## License
[MIT](./LICENSE)

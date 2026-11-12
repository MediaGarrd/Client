# Settings

Set these in `environment` in `docker-compose.yml`. Durations use ISO-8601, for example `PT12H`.

| Variable | Default | What it does |
|---|---|---|
| `CLIENT_PORT` | `8081` | The port of the web dashboard. Change the `ports` mapping to match. |
| `CLIENT_PICKUP_INTERVAL` | `PT12H` | How often the newest archive is downloaded automatically. Only the initial value: once it is saved in the dashboard, the stored value is used. |
| `CLIENT_BACKUP_RUN_TIMEOUT` | `PT6H` | How long "Run Backup Now" waits for the server to finish. Minimum `PT1M`. If a run takes longer, the dashboard shows an error, but the backup keeps running on the server. |
| `MEDIAGARRD_SERVER_IP` | (none) | A server to connect to automatically, as `<ip>` or `<ip>:<port>`. See [network.md](./network.md). |
| `MEDIAGARRD_SERVER_PORT` | `38471` | The server port tried first by "Scan Local Network" and "Connect by IP". |
| `MEDIAGARRD_UPDATE_REPOSITORY` | `MediaGarrd/Client` | The GitHub repository checked for new releases. |
| `MEDIAGARRD_GITHUB_API_URL` | `https://api.github.com` | The GitHub API used for the release check. |
| `MEDIAGARRD_UPDATE_CHECK_INTERVAL` | `PT24H` | How often the release check runs. Minimum `PT1H`. |
| `CLIENT_STATE_FILE` | `/var/lib/mediagarrd/client/state.json` | Where the client keeps its selected server, known servers, and pickup interval, inside the container. |
| `CLIENT_DOWNLOAD_DIRECTORY` | `/var/lib/mediagarrd/downloads` | Where downloaded archives are saved, inside the container. |

To change where data is kept on the host, change the left side of the volumes in `docker-compose.yml`:
- `./data/client/downloads`: downloaded archives.
- `./data/client/state`: the client's state. Keep it on persistent storage.

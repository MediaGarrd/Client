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

`CLIENT_PICKUP_INTERVAL` (ISO-8601, default `PT12H`) only sets the initial value. Once the interval has been saved in the web page, the stored value is used.

## Usage
- **Dashboard** connects to a MediaGarrd server by IP, from the list of known servers, or by scanning the local network. It shows the server's status, runs a backup, downloads backups, and sets how often the client downloads the latest backup on its own.
- **Server Config** changes the connected server's backup location, backup interval, and number of backups to keep, and adds, edits, and removes the services it backs up. The server must have finished its first-time setup (the `/config` page on the server) before it accepts changes from the client.

Every setting is stored in the `./data/client/state` volume and survives restarts and container rebuilds.

### Finding servers
"Scan Local Network" checks every address of the networks the client is attached to, on `MEDIAGARRD_SERVER_PORT`, `38471`, `8080`, and `80`, and lists every MediaGarrd server that answers. "Connect by IP" also accepts `<ip>:<port>`, and scans every port of the host when the server is not on one of those ports.

With the default Docker networking the client only sees the container network, so a scan finds servers running on the same host but not on other machines. Use "Connect by IP" or `MEDIAGARRD_SERVER_IP` for those, or add `network_mode: host` to the service (and remove its `ports` mapping) so the scan covers your LAN.

## Updating
```bash
git pull
docker compose up --build -d
```
Downloaded backups and the client state live in the mounted `./data/client` volumes and are kept.

## License
[GPLv3](./LICENSE)

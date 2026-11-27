# Client

The official client for [MediaGarrd](https://github.com/MediaGarrd/MediaGarrd). It is a web dashboard that connects to a [MediaGarrd Server](https://github.com/MediaGarrd/Server), shows its status live, runs and downloads backups, and changes the server's configuration.

Full documentation is on the [MediaGarrd website](https://mediagarrd.github.io/MediaGarrd/).

## Docs
- [settings.md](./docs/settings.md): every environment variable and its default.
- [https.md](./docs/https.md): HTTPS with nginx or Caddy, and self-signed certificates.
- [network.md](./docs/network.md): how the client finds servers on the network.
- [API.md](./docs/API.md): every HTTP endpoint and what it returns.
- [testing.md](./docs/testing.md): running the Java and browser tests.

## Requirements
- Docker with Compose v2
- A MediaGarrd Server from 11-19-26 or later that has finished its first-time setup. Older servers have no pairing, so this client cannot use them

The dashboard has a password and the client pairs with each server. Whoever opens a new dashboard first can choose its password, so do it right after installing, or set `CLIENT_PASSWORD`. Beyond a trusted network, put both behind HTTPS: see [https.md](./docs/https.md).

## Installation
```bash
git clone --depth 1 https://github.com/MediaGarrd/Client.git
cd Client
cp docker-compose.example.yml docker-compose.yml
docker compose up --build -d
```

Then open `http://<client-host>:8081` and choose a dashboard password (or set `CLIENT_PASSWORD`). Connect to your server by IP or with "Scan Local Network", then pair: enter the `Pairing PIN` that the server's setup page shows after Finish Setup, which it also prints in its logs (`docker logs mediagarrd-server 2>&1 | grep "Pairing PIN"`). To connect automatically, add `MEDIAGARRD_SERVER_IP=<server-ip>` to `environment` in `docker-compose.yml`.

The container runs the client as UID 1000, not as root. On first start it gives that user ownership of the mounted state and downloads folders (`./data/client/state` and `./data/client/downloads` in the example) and of the client's own files in them, so existing installs keep working. To use another owner, add `PUID` and `PGID` to `environment` in `docker-compose.yml` (see [settings](./docs/settings.md)).

## Updating
```bash
git pull
docker compose up --build -d
```

## License
[MIT](./LICENSE)

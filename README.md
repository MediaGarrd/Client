# Client

The official client for [MediaGarrd](https://github.com/MediaGarrd/MediaGarrd). It is a web dashboard that connects to a [MediaGarrd Server](https://github.com/MediaGarrd/Server), shows its status live, runs and downloads backups, and changes the server's configuration.

Full documentation is on the [MediaGarrd website](https://mediagarrd.github.io/MediaGarrd/).

## Docs
- [settings.md](./docs/settings.md): every environment variable and its default.
- [network.md](./docs/network.md): how the client finds servers on the network.
- [API.md](./docs/API.md): every HTTP endpoint and what it returns.
- [testing.md](./docs/testing.md): running the Java and browser tests.

## Requirements
- Docker with Compose v2
- A MediaGarrd Server that has finished its first-time setup

## Installation
```bash
git clone --depth 1 https://github.com/MediaGarrd/Client.git
cd Client
cp docker-compose.example.yml docker-compose.yml
docker compose up --build -d
```

Then open `http://<client-host>:8081` and connect to your server by IP or with "Scan Local Network". To connect automatically, add `MEDIAGARRD_SERVER_IP=<server-ip>` to `environment` in `docker-compose.yml`.

## Updating
```bash
git pull
docker compose up --build -d
```

## License
[MIT](./LICENSE)

# Finding servers on the network

The client can find MediaGarrd servers in three ways.

## Scan Local Network
"Scan Local Network" on the Dashboard checks every address of the networks the client is attached to.
- It tries `MEDIAGARRD_SERVER_PORT` first, then `38471`, `8080`, and `80`.
- An address counts as a MediaGarrd server only if it answers `GET /api/v1/health` like one.
- Networks larger than a `/24` are scanned as the `/24` around the client's own address.
- Loopback, Docker, VPN, and other virtual interfaces are skipped.
- A scan gives up after 20 seconds.
- Every server it finds is added to Known Servers.

## Connect by IP
"Connect by IP" accepts `<ip>` or `<ip>:<port>`. It tries the given port and the common ports above first. If none of them answer, it scans every port from 1024 to 65535 on that host.

## Connecting automatically
Set `MEDIAGARRD_SERVER_IP=<server-ip>` (or `<server-ip>:<port>`) in the client's `environment`. While no server is selected, the client tries that address every few seconds and connects as soon as it answers.

## Docker networking
With the default Docker networking the client only sees its container network. A scan then finds servers running on the same host, but not on other machines. To reach those:
- Use "Connect by IP" or `MEDIAGARRD_SERVER_IP`.
- Or add `network_mode: host` to the client's service in `docker-compose.yml`, and remove its `ports` mapping, so the scan covers your LAN.

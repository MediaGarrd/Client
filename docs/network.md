# Finding servers on the network

The client can find MediaGarrd servers in three ways.

## Scan Local Network
"Scan Local Network" on the Dashboard checks every address of the networks the client is attached to.
- It tries `MEDIAGARRD_SERVER_PORT` first, then `38471`, `8080`, and `80`.
- An address counts as a MediaGarrd server only if it answers `GET /api/v1/health` like one.
- Networks larger than a `/24` are scanned as the `/24` around the client's own address.
- Loopback, Docker, VPN, and other virtual interfaces are skipped.
- A scan gives up after 20 seconds.
- Every server it finds is added to Known Servers. Servers that are already known stay in the list, even when the scan does not find them again.

## Connect by IP
"Connect by IP" accepts `<ip>`, `<ip>:<port>`, or an `https://` address such as `https://garrd.example.com`. For an `https://` address the client uses the default port (443) unless you give one, checks only that address, and does not scan ports. The scan of the local network always uses plain HTTP. For a plain address it tries the given port and the common ports above first. If none of them answer, it scans every port from 1024 to 65535 on that host.

## Connecting automatically
Set `MEDIAGARRD_SERVER_IP=<server-ip>` (or `<server-ip>:<port>`, or `https://<host>`) in the client's `environment`. While no server is selected, the client tries that address every few seconds and connects as soon as it answers.

## Docker networking
With the default Docker networking the client only sees its container network. A scan then finds servers running on the same host, but not on other machines. To reach those:
- Use "Connect by IP" or `MEDIAGARRD_SERVER_IP`.
- Or add `network_mode: host` to the client's service in `docker-compose.yml`, and remove its `ports` mapping, so the scan covers your LAN. Keep `CLIENT_PORT` at 1024 or above in that case: the client does not run as root and cannot bind a lower port. If you also use `cap_drop: ALL`, keep `CHOWN`, `SETUID` and `SETGID`, or set `user:` instead (see [settings.md](./settings.md)).

## Pairing
Finding a server does not give access to it. Without a token the server's health check only says whether it is healthy and its backup interval; everything else needs the token you get by pairing. See [settings.md](./settings.md).

For HTTPS, see [https.md](./https.md).

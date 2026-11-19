# HTTPS

The Client dashboard and the Server both speak plain HTTP. To use HTTPS, put a reverse proxy in front of each. This matters because the dashboard password, the session cookie, the pairing PIN and the pairing tokens travel in plain text over HTTP. On a network you do not control, use HTTPS. The Server has its own page: [Server docs/https.md](https://github.com/MediaGarrd/Server/blob/main/docs/https.md). Read it too: it covers the Server side of this setup.

## 1. Keep the plain ports private
`docker-compose.example.yml` publishes the Client with `"8081:8081"`. Remove that mapping so the plain port is not reachable from outside, and put the Client and the proxy on a shared Docker network. The proxy reaches it by container name. The Server does the same with its `"38471:38471"` mapping.

```yaml
services:
  mediagarrd-client:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: mediagarrd-client
    environment:
      - CLIENT_PORT=8081
      - CLIENT_PICKUP_INTERVAL=PT12H
      - MEDIAGARRD_SERVER_PORT=38471
      - MEDIAGARRD_SERVER_IP=https://mediagarrd.example.com
    volumes:
      - "./data/client/downloads:/var/lib/mediagarrd/downloads"
      - "./data/client/state:/var/lib/mediagarrd/client"
    networks:
      - proxy
    restart: unless-stopped

networks:
  proxy:
    name: proxy
```

Run the proxy on the same network (`proxy`) and send traffic to `mediagarrd-client:8081`.

## 2. Serve the dashboard at the root of a domain
The dashboard uses absolute paths (`/js/main.js`, `/api/client/...`). It must be served at the root of a domain or subdomain, for example `https://client.example.com`. It does not work under a subpath such as `https://example.com/mediagarrd/`.

The proxy must pass the `Host` header on unchanged (or send `X-Forwarded-Host`) and, for the cookie, tell the Client the scheme with `X-Forwarded-Proto: https`. The Client uses the first to accept changing requests from the dashboard and the second to mark the session cookie `Secure`. Caddy does both by default; nginx needs the two `proxy_set_header` lines below.

### Caddy
```
client.example.com {
    reverse_proxy mediagarrd-client:8081 {
        flush_interval -1
    }
}
```
`flush_interval -1` sends the live event stream (`/api/client/events`) through immediately.

### nginx
```nginx
server {
    listen 443 ssl http2;
    server_name client.example.com;

    ssl_certificate     /etc/ssl/mediagarrd/fullchain.pem;
    ssl_certificate_key /etc/ssl/mediagarrd/privkey.pem;

    location / {
        proxy_pass http://mediagarrd-client:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```
On nginx 1.25.1 or newer, `http2 on;` as its own directive replaces the `http2` parameter on `listen`; the parameter still works there but is deprecated. `proxy_buffering off` lets the event stream through. The long timeouts matter for "Run Backup Now", which holds a request open until the backup finishes (up to `CLIENT_BACKUP_RUN_TIMEOUT`, default 6 hours), so raise them if your backups take longer.

Behind a proxy the Client sees every visitor as coming from the proxy, so the failed-login delay is shared by all of them.

## 3. Serve the Server
Follow the Server's page for its proxy (`https://mediagarrd.example.com` in the examples). Large backup downloads and backup runs need long timeouts there too: nginx `proxy_read_timeout` and `proxy_send_timeout`, and `proxy_buffering off`. Caddy sets no time limit.

## 4. Connect the Client to the HTTPS Server
Type the address with its scheme in "Connect by IP", or set `MEDIAGARRD_SERVER_IP=https://mediagarrd.example.com`. The scheme is kept, and without a port the Client uses 443 and checks only that address; it does not scan ports. "Scan Local Network" only looks for plain HTTP servers.

A token is tied to the exact address it was paired on. Switching from `http://<ip>:38471` to `https://mediagarrd.example.com` means pairing again with a PIN or a pairing code.

The Client does not follow redirects when it talks to the Server, so a proxy rule that redirects HTTP to HTTPS makes an `http://` address fail. For a Server that is already selected the dashboard reports the address the redirect points to; while connecting ("Connect by IP" or `MEDIAGARRD_SERVER_IP`) you only see that no server was found. Use the `https://` address.

### Certificates from your own authority or self-signed
A certificate from a public authority (Let's Encrypt, Caddy's automatic certificates, Tailscale) needs nothing extra. For a private or self-signed certificate, give the Client the certificate authority, or the self-signed certificate itself, as a PEM file:

```yaml
    environment:
      - MEDIAGARRD_SERVER_CA=/etc/mediagarrd/ca.pem
    volumes:
      - "./ca.pem:/etc/mediagarrd/ca.pem:ro"
```
- The file may hold several certificates. They are trusted in addition to the system ones, only for connections to the Server; the GitHub update check is not affected.
- The file must be readable by the user the Client runs as: UID 1000, or your `PUID`. Mode `0644` works; a `0600` file owned by root does not. The Client does not start if the file cannot be read or holds no certificate, and says why.
- The host name is still checked. If you reach the Server by IP address, the certificate needs that IP in its Subject Alternative Names (for example `-addext "subjectAltName=IP:192.168.1.10"` with `openssl req`).

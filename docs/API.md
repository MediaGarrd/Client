# Client API

Endpoints are under `/api/client` and `/api/auth`. Endpoints that act on the connected server need a server to be selected. Errors are returned as `{"error": "..."}` with a matching status code. A `5xx` caused by the client itself, or reported by the server, carries no detail (`{"error": "Internal error"}` or `{"error": "MediaGarrd server responded with <status>"}`), with one exception: a failed `POST /api/client/backups/run` answers `500` with the run's failure reason in `error`, because the dashboard shows it. The documented `502`, `503` and `504` answers below keep their messages. A request body over 64 KiB, declared or chunked, is refused with `413`.

## Login
Everything under `/api/client`, including `GET /events`, needs the dashboard session cookie (`mg_session`, `HttpOnly`, `SameSite=Strict`, `Secure` over HTTPS or when the proxy sends `X-Forwarded-Proto: https`). Without it the client answers `401` with `{"error": "Login required", "login": "required"}`. Requests that change something (anything but GET) are refused with `403` when a browser marks them as cross-site: `Sec-Fetch-Site` must be `same-origin`, or `Origin` must match the `Host` (or `X-Forwarded-Host`) header. A request with neither header must carry `X-MediaGarrd-Request: 1`; the dashboard sends it on every call, and scripts must add it to every non-GET request.

| Endpoint | Returns |
|---|---|
| `GET /api/auth/status` | `{"passwordSet", "passwordFromEnvironment", "authenticated"}`. No session needed. |
| `POST /api/auth/login` | `{"authenticated": true}` and the session cookie for body `{"password": "..."}`. `401` for a wrong password, `409` when no password is set, `429` with `Retry-After` while the source address is locked out after repeated failures, while another attempt from it is still being checked, when several passwords are already being checked, or when all addresses together have used up their 30 password checks a minute. |
| `POST /api/auth/set-password` | Sets the first password (body `{"password": "..."}`, 8 to 256 characters) and signs in. `409` when a password already exists or `CLIENT_PASSWORD` is set. |
| `POST /api/auth/logout` | An empty `204` and an expired cookie. |

A headless script logs in with `curl -c jar -H 'X-MediaGarrd-Request: 1' -H 'Content-Type: application/json' -d '{"password":"..."}' http://<client>:8081/api/auth/login` and sends `-b jar -H 'X-MediaGarrd-Request: 1'` on every later non-GET request.

## Pairing
The client keeps one token per server address. Tokens never appear in any response. When the server has no token for the selected address, or the server rejects it (`401`), a request that needs the server is answered with `502` and `{"error": "...", "pairing": "required"}`. `GET /dashboard` and the `snapshot` event carry `pairing`: `NONE` (no server), `PAIRED`, `REQUIRED` (no token stored) or `REJECTED` (the server refused the stored token); the `pairing` event carries `{"state": ...}`.

| Endpoint | Returns |
|---|---|
| `POST /pairing` | `{"paired": true, "name": "...", "note": null}` after pairing with the selected server. Answers `409` while this client holds a stored token that the server still accepts (the client probes it with `GET /api/v1/health`); unpair first. A pair or unpair request waits while another one is running. A stored token the server refuses (`401`) is replaced. Body `{"pin": "<PIN or pairing code>", "name": "<optional label>"}`. The client pairs, stores the token, then confirms it with one authenticated `GET /api/v1/health` on the server (retried up to 3 times after a network error or a 502, 503 or 504). A `401` on the confirmation, or a reply that is not a confirmation, discards the token and is answered with a `400` (or `502`), so the same PIN or code can be tried again. Any other failure keeps the token and answers `{"paired": true, "name": "...", "note": "..."}`, where `note` says the server will confirm it on the next connection; `note` is `null` after a confirmed pairing. A wrong PIN or code is a `400`; the server's `403` (pairing closed), `409` (setup not finished) and `429` (locked) are passed on with its message. |
| `POST /pairing/code` | `{"code": "XXXXX-XXXXX", "expiresAt": "..."}`: a one-time code, valid for 10 minutes, that pairs another client. Shown only in this response. |
| `DELETE /pairing` | `{"serverRevoked": true/false, "warning": null or "..."}`. Revokes this client's token on the server and forgets it locally. It waits while a pair request is running. `{"serverRevoked": false, "warning": "..."}` when the server could not be told (unreachable, token rejected, or no client id stored): the token is forgotten locally anyway and `warning` says the server may still list this client. `warning` is `null` when `serverRevoked` is `true`. |


## Dashboard
| Endpoint | Returns |
|---|---|
| `GET /status` | The selected server, the known servers, and the pickup interval. |
| `GET /dashboard` | The dashboard state: selected and known servers, connection state, run state, the server's schedule and retention, and both versions. |
| `GET /events` | A Server-Sent Events stream that starts with the dashboard state and then pushes each change to it, including `pairing`. |

## Servers
| Endpoint | Returns |
|---|---|
| `POST /discover` | The servers found by scanning the local network. |
| `PUT /server` | The client status after selecting a server by URL, or by IP and optional port. A URL that is not already known is checked first: if no MediaGarrd server answers there the client answers `400`. An address that starts with `https://` keeps its scheme and, without a port, uses 443; no port scan is done for it. |

## Backups
| Endpoint | Returns |
|---|---|
| `GET /backups` | The connected server's archives. |
| `POST /backups/run` | An empty `200` once the connected server finishes a backup, or `504` if it takes longer than the run timeout. |
| `POST /pickup` | A task id and the archive's details after starting a download of the newest archive. `400` if the server gives the archive a file name that does not end in `.zip` or ends in `.part`. |
| `POST /pickup/{id}` | A task id and the archive's details after starting a download of one archive. `400` for the same file name check. |
| `GET /pickup/progress/{taskId}` | A download's status, bytes written, total size, and saved path. |
| `PUT /pickup-interval` | The client status after changing how often the newest archive is downloaded automatically. |

## Server configuration
| Endpoint | Returns |
|---|---|
| `GET /server-config` | The connected server's backup location, number of backups to keep, and time between backups. |
| `PUT /server-config` | The connected server's updated configuration. A `backupInterval` shorter than 15 minutes or longer than 365 days is refused with `400` (`Backup interval must be between 15 minutes and 365 days`) before anything is sent to the server, so the location and retention are not changed either. |
| `GET /services` | The connected server's services with their host paths. |
| `POST /services` | The service added to the connected server, with `201`. |
| `PUT /services/{name}` | The updated service on the connected server. |
| `DELETE /services/{name}` | An empty `204` once the service is removed from the connected server. |

## Failure webhook
Proxies the connected server's failure webhook (see the server's API.md), which the server calls when a backup fails or only partly succeeds. The full URL is a secret, so it is never returned: only whether one is set and its `scheme://host[:port]`. A write needs the same `X-MediaGarrd-Request: 1` header as the other writes.

| Endpoint | Returns |
|---|---|
| `GET /notifications/webhook` | `{"configured": true, "target": "https://discord.com"}` or `{"configured": false, "target": null}`. |
| `PUT /notifications/webhook` | The same shape after saving. Body `{"url": "https://..."}`; an empty string clears the webhook. An invalid URL is a `400` with the server's message. |
| `POST /notifications/webhook/test` | `{"delivered": false, "status": 404, "error": "Webhook answered 404"}` after the server sends a test message (`status` is the receiver's 2xx code when delivered and `null` only when no reply came, and `error` is `null` when delivered). A test less than 10 seconds after the last one is a `429` with the server's message and its `Retry-After` header. `400` when no webhook is set. |

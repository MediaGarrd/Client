# Client API

All endpoints are under `/api/client`. Endpoints that act on the connected server need a server to be selected. Errors are returned as `{"error": "..."}` with a matching status code.

## Dashboard
| Endpoint | Returns |
|---|---|
| `GET /status` | The selected server, the known servers, and the pickup interval. |
| `GET /dashboard` | The dashboard state: selected and known servers, connection state, run state, the server's schedule and retention, and both versions. |
| `GET /events` | A Server-Sent Events stream that starts with the dashboard state and then pushes each change to it. |

## Servers
| Endpoint | Returns |
|---|---|
| `POST /discover` | The servers found by scanning the local network. |
| `PUT /server` | The client status after selecting a server by URL, or by IP and optional port. |

## Backups
| Endpoint | Returns |
|---|---|
| `GET /backups` | The connected server's archives. |
| `POST /backups/run` | An empty `200` once the connected server finishes a backup, or `504` if it takes longer than the run timeout. |
| `POST /pickup` | A task id and the archive's details after starting a download of the newest archive. |
| `POST /pickup/{id}` | A task id and the archive's details after starting a download of one archive. |
| `GET /pickup/progress/{taskId}` | A download's status, bytes written, total size, and saved path. |
| `PUT /pickup-interval` | The client status after changing how often the newest archive is downloaded automatically. |

## Server configuration
| Endpoint | Returns |
|---|---|
| `GET /server-config` | The connected server's backup location, number of backups to keep, and time between backups. |
| `PUT /server-config` | The connected server's updated configuration. |
| `GET /services` | The connected server's services with their host paths. |
| `POST /services` | The service added to the connected server, with `201`. |
| `PUT /services/{name}` | The updated service on the connected server. |
| `DELETE /services/{name}` | An empty `204` once the service is removed from the connected server. |

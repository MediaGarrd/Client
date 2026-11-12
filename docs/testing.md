# Testing

`make test` runs both the Java tests and the browser tests.

## Browser tests
The browser tests cover the pure modules in `src/main/resources/static/js`:
- `dashboard-model.js`: how events change the dashboard state, and when the archive list reloads.
- `view-model.js`: how the connection state, known servers, and versions are rendered.
- `reachability.js`: when the client counts as unreachable.

They need Node 20.19 or newer and have no dependencies. Run them on their own from the repository root:
```bash
node --test src/test/js/*.test.mjs
```

## Java tests
```bash
./gradlew test
```

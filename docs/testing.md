# Testing

`make test` runs both the Java tests and the browser tests.

## Browser tests
The browser tests in `src/test/js` cover the pure modules in `src/main/resources/static/js` (dashboard state, view and message logic, formatting, API error handling, the event stream, busy handling, notifications, the webhook form, and login and pairing logic). Some also read the HTML, CSS or Java sources to check what they contain, such as the webhook panel's markup and styles and that the event names match the server's. The files in `src/test/js` are the list of what is covered.

They need Node 20.19 or newer and have no dependencies. Run them on their own from the repository root:
```bash
node --test src/test/js/*.test.mjs
```

## Manual UI check
The page wiring (`auth.js`, `pairing.js`, `main.js`, the HTML and CSS) is not covered by automated tests; only the pure modules are. After changing the page, open the dashboard in a browser and check: setting the first password, logging in and out, pairing with a PIN, creating a pairing code, unpairing, and that a revoked token shows the pairing form again.

## Java tests
```bash
./gradlew test
```
The HTTPS tests generate a self-signed certificate with the JDK's `keytool`, so the JDK's `bin` directory must be present.

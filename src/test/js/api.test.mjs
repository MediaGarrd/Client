import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { api, errorMessage, onLoginRequired, onPairingRequired, retryAfterSeconds, toApiError } from '../../main/resources/static/js/api.js';

const realFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = realFetch;
  onLoginRequired(() => {});
  onPairingRequired(() => {});
});

function respond(status, body, headers = {}) {
  globalThis.fetch = async () => new Response(typeof body === 'string' ? body : JSON.stringify(body), {
    status,
    headers: new Headers(headers),
  });
}

test('the error contract maps to its message and Spring default bodies prefer the detail message, then the reason phrase', () => {
  assert.equal(errorMessage({ error: 'No active MediaGarrd server selected' }, 'x'), 'No active MediaGarrd server selected');
  const spring = { timestamp: 't', status: 400, error: 'Bad Request', path: '/x' };
  assert.equal(errorMessage({ ...spring, message: 'Name is required' }, 'x'), 'Name is required');
  assert.equal(errorMessage({ ...spring, message: '' }, 'x'), 'Bad Request');
  assert.equal(errorMessage(spring, 'x'), 'Bad Request');
});

test('a missing, empty or non-object body falls back to the generic message', () => {
  assert.equal(errorMessage({}, 'GET /x failed (500)'), 'GET /x failed (500)');
  assert.equal(errorMessage(null, 'fb'), 'fb');
  assert.equal(errorMessage('text', 'fb'), 'fb');
  assert.equal(errorMessage({ error: 42, message: '  ' }, 'fb'), 'fb');
});

test('Retry-After accepts seconds and HTTP dates and rejects the rest', () => {
  assert.equal(retryAfterSeconds('30'), 30);
  assert.equal(retryAfterSeconds(' 7 '), 7);
  assert.equal(retryAfterSeconds('0'), 0);
  const now = Date.parse('2026-01-10T12:00:00Z');
  assert.equal(retryAfterSeconds('Sat, 10 Jan 2026 12:01:00 GMT', now), 60);
  assert.equal(retryAfterSeconds('Sat, 10 Jan 2026 11:00:00 GMT', now), 0);
  assert.equal(retryAfterSeconds('soon'), null);
  assert.equal(retryAfterSeconds(''), null);
  assert.equal(retryAfterSeconds(null), null);
  assert.equal(retryAfterSeconds(undefined), null);
});

test('toApiError carries status, pairing flag and retry delay', () => {
  const error = toApiError({
    method: 'POST', url: '/api/auth/login', status: 429,
    data: { error: 'Too many login attempts, try again in 12 seconds' }, retryAfter: '12',
  });
  assert.equal(error.message, 'Too many login attempts, try again in 12 seconds');
  assert.equal(error.status, 429);
  assert.equal(error.retryAfter, 12);
  assert.equal(error.pairing, undefined);
  assert.equal(toApiError({ method: 'GET', url: '/x', status: 502, data: { error: 'm', pairing: 'required' } }).pairing, 'required');
  assert.equal(toApiError({ method: 'GET', url: '/x', status: 500, data: {} }).message, 'The client answered with an error (500).');
  assert.equal(toApiError({ method: 'POST', url: '/x', status: 504, data: {} }).message, 'The client or a proxy in front of it did not respond in time.');
  assert.equal(toApiError({ method: 'POST', url: '/x', status: 502, data: {} }).message, 'The client or a proxy in front of it did not respond in time.');
  assert.equal(toApiError({ status: 504, data: { error: 'Backup timed out' } }).message, 'Backup timed out');
});

test('a 401 with login required fires the login hook once and still throws', async () => {
  let fired = 0;
  onLoginRequired(() => { fired++; });
  respond(401, { login: 'required', error: 'Sign in' });
  await assert.rejects(api.dashboard(), (error) => error.status === 401 && error.message === 'Sign in');
  assert.equal(fired, 1);
});

test('a 401 without the login marker does not fire the login hook', async () => {
  let fired = 0;
  onLoginRequired(() => { fired++; });
  respond(401, { error: 'Incorrect password' });
  await assert.rejects(api.login('x'), /Incorrect password/);
  assert.equal(fired, 0);
});

test('a 502 pairing required fires the pairing hook', async () => {
  let fired = 0;
  onPairingRequired(() => { fired++; });
  respond(502, { error: 'Pair first', pairing: 'required' });
  await assert.rejects(api.listBackups(), /Pair first/);
  assert.equal(fired, 1);
});

test('a non-JSON error body still produces a useful error with the Retry-After value', async () => {
  respond(503, '<html>bad gateway</html>', { 'Retry-After': '9' });
  await assert.rejects(api.status(), (error) => error.status === 503 && error.retryAfter === 9 && error.message === 'The client answered with an error (503).');
});

test('successful empty and JSON responses are parsed', async () => {
  respond(200, '');
  assert.equal(await api.logout(), null);
  respond(200, { authenticated: true });
  assert.deepEqual(await api.authStatus(), { authenticated: true });
});

test('requests send the CSRF header and JSON bodies', async () => {
  let seen;
  globalThis.fetch = async (url, init) => {
    seen = { url, init };
    return new Response('{}', { status: 200 });
  };
  await api.pair('123456');
  assert.equal(seen.url, '/api/client/pairing');
  assert.equal(seen.init.method, 'POST');
  assert.equal(seen.init.headers['X-MediaGarrd-Request'], '1');
  assert.equal(seen.init.body, JSON.stringify({ pin: '123456' }));
  await api.pickupById('a/b c');
  assert.equal(seen.url, '/api/client/pickup/a%2Fb%20c');
});

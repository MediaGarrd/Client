import assert from 'node:assert/strict';
import { afterEach, beforeEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { describeStatus, describeTest, initWebhook } from '../../main/resources/static/js/webhook.js';

const realFetch = globalThis.fetch;
const savedDocument = globalThis.document;
let calls;
let replies;

beforeEach(() => {
  calls = [];
  replies = [];
  globalThis.document = { activeElement: null };
  globalThis.fetch = async (url, init) => {
    calls.push({ url, method: init.method, headers: init.headers, body: init.body });
    const reply = replies.shift() || { status: 200, body: {} };
    return new Response(JSON.stringify(reply.body), { status: reply.status, headers: new Headers(reply.headers || {}) });
  };
});

afterEach(() => {
  globalThis.fetch = realFetch;
  if (savedDocument === undefined) delete globalThis.document;
  else globalThis.document = savedDocument;
});

function button() {
  const listeners = {};
  return { disabled: false, listeners, addEventListener(name, fn) { listeners[name] = fn; } };
}

function setup() {
  const url = { value: '' };
  const label = { textContent: '' };
  const parts = { '[data-webhook-status]': label, '[data-webhook-test]': button(), '[data-webhook-clear]': button(), 'button[type="submit"]': button() };
  const form = {
    elements: { url },
    listeners: {},
    addEventListener(name, fn) { this.listeners[name] = fn; },
    querySelector: (selector) => parts[selector],
  };
  const messages = [];
  const notify = {
    ok: (message) => messages.push(['ok', message]),
    error: (error) => messages.push(['error', error.message]),
    clear: () => messages.push(['clear']),
  };
  const view = initWebhook(form, notify);
  return { form, url, label, parts, messages, view };
}

const submit = (form) => form.listeners.submit({ preventDefault() {} });
const settled = (messages) => messages.filter((entry) => entry[0] !== 'clear');

test('a configured webhook shows only its target and an unset one says so', () => {
  assert.equal(describeStatus({ configured: true, target: 'https://discord.com' }), 'Configured: https://discord.com');
  assert.equal(describeStatus({ configured: false, target: null }), 'Not configured');
  assert.equal(describeStatus(null), 'Not configured');
});

test('a test result names the reason when it was not delivered', () => {
  assert.deepEqual(describeTest({ delivered: true, status: 200, error: null }), { ok: true, message: 'Test message delivered' });
  assert.deepEqual(describeTest({ delivered: false, status: 404, error: 'Webhook answered 404' }),
    { ok: false, message: 'Test message was not delivered: Webhook answered 404' });
  assert.equal(describeTest({ delivered: false }).ok, false);
});

test('loading shows the target and never fills the input', async () => {
  const { url, label, view } = setup();
  replies.push({ status: 200, body: { configured: true, target: 'https://hooks.slack.com' } });
  await view.load();
  assert.equal(label.textContent, 'Configured: https://hooks.slack.com');
  assert.equal(url.value, '');
  assert.equal(calls[0].method, 'GET');
  assert.equal(calls[0].url, '/api/client/notifications/webhook');
});

test('a failed load says it is unavailable instead of breaking the page', async () => {
  const { label, view } = setup();
  replies.push({ status: 404, body: { error: 'Not Found' } });
  await view.load();
  assert.equal(label.textContent, 'Unavailable: Not Found');
});

test('saving sends the typed URL with the CSRF header, clears the input and shows the target', async () => {
  const { form, url, label, messages } = setup();
  url.value = '  https://discord.com/api/webhooks/1/SECRET  ';
  replies.push({ status: 200, body: { configured: true, target: 'https://discord.com' } });
  await submit(form);
  assert.equal(calls[0].method, 'PUT');
  assert.equal(calls[0].url, '/api/client/notifications/webhook');
  assert.equal(calls[0].headers['X-MediaGarrd-Request'], '1');
  assert.deepEqual(JSON.parse(calls[0].body), { url: 'https://discord.com/api/webhooks/1/SECRET' });
  assert.equal(url.value, '');
  assert.equal(label.textContent, 'Configured: https://discord.com');
  assert.deepEqual(settled(messages), [['ok', 'Webhook saved']]);
});

test('saving an empty field makes no request and points to Clear', async () => {
  const { form, messages } = setup();
  await submit(form);
  assert.equal(calls.length, 0);
  assert.equal(settled(messages)[0][0], 'error');
});

test('a rejected URL shows the server message and keeps the typed text', async () => {
  const { form, url, messages } = setup();
  url.value = 'ftp://example.com';
  replies.push({ status: 400, body: { error: 'Webhook URL must use http or https' } });
  await submit(form);
  assert.equal(url.value, 'ftp://example.com');
  assert.deepEqual(settled(messages), [['error', 'Webhook URL must use http or https']]);
});

test('clearing sends an empty URL', async () => {
  const { parts, label, messages } = setup();
  replies.push({ status: 200, body: { configured: false, target: null } });
  await parts['[data-webhook-clear]'].listeners.click();
  assert.deepEqual(JSON.parse(calls[0].body), { url: '' });
  assert.equal(label.textContent, 'Not configured');
  assert.deepEqual(settled(messages), [['ok', 'Webhook cleared']]);
});

test('Send test reports a delivered message', async () => {
  const { parts, messages } = setup();
  replies.push({ status: 200, body: { delivered: true, status: 200, error: null } });
  await parts['[data-webhook-test]'].listeners.click();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].method, 'POST');
  assert.equal(calls[0].url, '/api/client/notifications/webhook/test');
  assert.equal(calls[0].headers['X-MediaGarrd-Request'], '1');
  assert.deepEqual(settled(messages), [['ok', 'Test message delivered']]);
});

test('Send test saves a typed URL first and reports a failed delivery', async () => {
  const { parts, url, messages } = setup();
  url.value = 'https://example.com/hook';
  replies.push({ status: 200, body: { configured: true, target: 'https://example.com' } });
  replies.push({ status: 200, body: { delivered: false, status: 404, error: 'Webhook answered 404' } });
  await parts['[data-webhook-test]'].listeners.click();
  assert.deepEqual(calls.map((call) => call.method), ['PUT', 'POST']);
  assert.deepEqual(settled(messages), [['error', 'Test message was not delivered: Webhook answered 404']]);
});

test('a test inside the cooldown shows the wait from the server', async () => {
  const { parts, messages } = setup();
  replies.push({ status: 429, body: { error: 'Wait 7 seconds before sending another test' }, headers: { 'Retry-After': '7' } });
  await parts['[data-webhook-test]'].listeners.click();
  assert.deepEqual(settled(messages), [['error', 'Wait 7 seconds before sending another test']]);
});

test('the buttons are busy while a request runs and restored after', async () => {
  const { parts } = setup();
  const test = parts['[data-webhook-test]'];
  let during;
  globalThis.fetch = async () => {
    during = test.disabled;
    return new Response(JSON.stringify({ delivered: true }), { status: 200 });
  };
  await test.listeners.click();
  assert.equal(during, true);
  assert.equal(test.disabled, false);
});

test('the other buttons are disabled while one action runs and a second action is ignored', async () => {
  const { parts } = setup();
  const test = parts['[data-webhook-test]'];
  const clear = parts['[data-webhook-clear]'];
  let release;
  globalThis.fetch = () => new Promise((resolve) => { release = () => resolve(new Response(JSON.stringify({ delivered: true }), { status: 200 })); });
  const running = test.listeners.click();
  assert.equal(clear.disabled, true);
  assert.equal(parts['button[type="submit"]'].disabled, true);
  const ignored = await clear.listeners.click();
  assert.equal(ignored, undefined);
  release();
  await running;
  assert.equal(clear.disabled, false);
  assert.equal(parts['button[type="submit"]'].disabled, false);
});

test('a save that finishes after a server switch does not change the new server label', async () => {
  const { form, url, label, view } = setup();
  url.value = 'https://example.com/hook';
  let release;
  globalThis.fetch = () => new Promise((resolve) => { release = () => resolve(new Response(JSON.stringify({ configured: true, target: 'https://example.com' }), { status: 200 })); });
  const pending = submit(form);
  view.reset();
  release();
  await pending;
  assert.equal(label.textContent, 'Checking…');
});

test('switching servers drops the displayed state', () => {
  const { url, label, view } = setup();
  url.value = 'https://x.example/y';
  view.reset();
  assert.equal(url.value, '');
  assert.equal(label.textContent, 'Checking…');
});

test('the page has the webhook panel with a never-prefilled url input', () => {
  const html = readFileSync(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
  assert.match(html, /id="webhookForm"/);
  assert.match(html, /id="webhookUrl" name="url" type="url" autocomplete="off"/);
  assert.match(html, /data-webhook-test/);
  assert.match(html, /data-webhook-clear/);
  assert.doesNotMatch(html, /id="webhookUrl"[^>]*value=/);
});

test('a note followed by a field group gets the same gap as between field groups', () => {
  const css = readFileSync(new URL('../../main/resources/static/css/mediagarrd.css', import.meta.url), 'utf8');
  assert.match(css, /\.note \+ \.field-group \{ margin-top: 0\.75rem; \}/);
  const html = readFileSync(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
  assert.match(html, /<\/p>\s*<div class="field-group">\s*<label class="field-label" for="newPassword">/);
});

test('the server config view has Backup Settings alone, then a stack of Add Service and Failure Notifications', () => {
  const html = readFileSync(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
  const config = html.slice(html.indexOf('data-config-content'));
  const backup = config.indexOf('Backup Settings');
  const stack = config.indexOf('<div class="stack">');
  const services = config.indexOf('id="services"');
  const webhook = config.indexOf('id="webhook"');
  assert.ok(backup > 0 && backup < stack);
  assert.ok(stack < services && services < webhook);
  assert.equal(config.slice(0, stack).match(/<section class="panel/g).length, 1);
});

test('grid tracks and items may shrink below their content so the page never scrolls sideways', () => {
  const css = readFileSync(new URL('../../main/resources/static/css/mediagarrd.css', import.meta.url), 'utf8');
  assert.match(css, /grid-template-columns: 360px minmax\(0, 1fr\);/);
  assert.match(css, /@media \(max-width: 900px\) \{ \.workspace \{ grid-template-columns: minmax\(0, 1fr\); \} \}/);
  assert.match(css, /\.workspace > \* \{ min-width: 0; \}/);
  assert.match(css, /\.input-row \{[^}]*minmax\(0, 1fr\) auto/);
  assert.match(css, /input, select \{\s*width: 100%;\s*min-width: 0;/);
});

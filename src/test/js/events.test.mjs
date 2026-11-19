import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { EVENT_NAMES, initEvents } from '../../main/resources/static/js/events.js';

const JAVA_ROOT = fileURLToPath(new URL('../../main/java', import.meta.url));

function javaFiles(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? javaFiles(path) : path.endsWith('.java') ? [path] : [];
  });
}

function publishedByJava() {
  const names = new Set();
  const sources = javaFiles(JAVA_ROOT).map((file) => readFileSync(file, 'utf8'));
  for (const source of sources) {
    for (const match of source.matchAll(/\b(?:publish|publishRaw)\(\s*"([a-z][a-z-]*)"/g)) names.add(match[1]);
    for (const match of source.matchAll(/new ClientEvent\(\s*"([a-z][a-z-]*)"/g)) names.add(match[1]);
  }
  const stream = sources.find((source) => source.includes('FORWARDED_EVENTS = Set.of('));
  const forwarded = /FORWARDED_EVENTS = Set\.of\(([^)]*)\)/.exec(stream)[1].match(/"[a-z-]+"/g).map((quoted) => quoted.slice(1, -1));
  forwarded.filter((name) => name !== 'config-changed').forEach((name) => names.add(name));
  names.delete('close');
  return names;
}

test('EVENT_NAMES covers every event the Java client publishes, and nothing else', () => {
  const published = publishedByJava();
  assert.ok(published.size >= 10, `expected to find the published events, found ${[...published]}`);
  assert.deepEqual([...EVENT_NAMES].sort(), [...published].sort());
});

class FakeSource {
  static CLOSED = 2;
  static instances = [];
  constructor(url) {
    this.url = url;
    this.readyState = 1;
    this.listeners = {};
    this.closed = false;
    FakeSource.instances.push(this);
  }
  addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); }
  close() { this.closed = true; }
  emit(name, event = {}) { (this.listeners[name] || []).forEach((handler) => handler(event)); }
}

const saved = {};
beforeEach(() => {
  FakeSource.instances = [];
  for (const key of ['EventSource', 'document', 'window', 'setInterval', 'setTimeout']) saved[key] = globalThis[key];
  globalThis.EventSource = FakeSource;
  globalThis.document = { hidden: false, addEventListener() {} };
  globalThis.window = { addEventListener() {} };
  globalThis.setInterval = () => 0;
  globalThis.setTimeout = () => 0;
});
afterEach(() => {
  for (const key of Object.keys(saved)) {
    if (saved[key] === undefined) delete globalThis[key];
    else globalThis[key] = saved[key];
  }
});

test('every declared event name is subscribed, including pairing', () => {
  initEvents({ onEvent() {}, onConnection() {} });
  const [source] = FakeSource.instances;
  assert.deepEqual(EVENT_NAMES.filter((name) => !source.listeners[name]), []);
  assert.ok(source.listeners.pairing);
});

test('a pairing event reaches the handler as parsed JSON and pings are swallowed', () => {
  const seen = [];
  initEvents({ onEvent: (name, payload) => seen.push([name, payload]), onConnection() {} });
  const [source] = FakeSource.instances;
  source.emit('pairing', { data: '{"state":"REJECTED"}' });
  source.emit('ping', { data: '{}' });
  source.emit('link', { data: 'not json' });
  assert.deepEqual(seen, [['pairing', { state: 'REJECTED' }]]);
});

test('a stream refused before it ever opened checks the session', async () => {
  let checks = 0;
  const connection = [];
  initEvents({ onEvent() {}, onConnection: (reachable) => connection.push(reachable), checkSession: async () => { checks++; } });
  const [source] = FakeSource.instances;
  source.readyState = FakeSource.CLOSED;
  source.emit('error');
  assert.equal(checks, 1);
  assert.equal(source.closed, true);
  assert.deepEqual(connection, [false]);
});

test('a stream that dropped after opening does not check the session', () => {
  let checks = 0;
  initEvents({ onEvent() {}, onConnection() {}, checkSession: async () => { checks++; } });
  const [source] = FakeSource.instances;
  source.emit('open');
  source.readyState = FakeSource.CLOSED;
  source.emit('error');
  assert.equal(checks, 0);
});

test('a refused reconnect after a stream that was open checks the session', () => {
  let checks = 0;
  initEvents({ onEvent() {}, onConnection() {}, checkSession: async () => { checks++; } });
  const [source] = FakeSource.instances;
  source.emit('open');
  source.readyState = 0;
  source.emit('error');
  assert.equal(checks, 0);
  source.readyState = FakeSource.CLOSED;
  source.emit('error');
  assert.equal(checks, 1);
});

test('a reconnect that opened again does not check the session when it later drops', () => {
  let checks = 0;
  initEvents({ onEvent() {}, onConnection() {}, checkSession: async () => { checks++; } });
  const [source] = FakeSource.instances;
  source.emit('open');
  source.readyState = 0;
  source.emit('error');
  source.readyState = 1;
  source.emit('open');
  source.readyState = FakeSource.CLOSED;
  source.emit('error');
  assert.equal(checks, 0);
});

test('a failing session check is swallowed and the stream is still reopened', async () => {
  let checks = 0;
  let reopens = 0;
  const unhandled = [];
  const onUnhandled = (reason) => unhandled.push(reason);
  process.on('unhandledRejection', onUnhandled);
  globalThis.setTimeout = () => { reopens++; return 1; };
  try {
    initEvents({ onEvent() {}, onConnection() {}, checkSession: async () => { checks++; throw new Error('down'); } });
    const [source] = FakeSource.instances;
    source.readyState = FakeSource.CLOSED;
    source.emit('error');
    await new Promise((resolve) => setImmediate(resolve));
  } finally {
    process.off('unhandledRejection', onUnhandled);
  }
  assert.equal(checks, 1);
  assert.equal(reopens, 1);
  assert.deepEqual(unhandled, []);
});

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { emptyState, reduce } from '../../main/resources/static/js/dashboard-model.js';
import { classifyVersion, configUnavailableMessage, linkView, listingError, planKnown, versionsView } from '../../main/resources/static/js/view-model.js';

const loaded = (overrides = {}) => reduce(emptyState(), 'snapshot', {
  activeServer: 'http://a:38471',
  knownServers: ['http://a:38471'],
  link: 'LIVE',
  running: false,
  health: { backupInterval: 'PT6H', backupRetentionCount: 5 },
  versions: null,
  ...overrides,
});

test('before the first snapshot the page says it is connecting', () => {
  const view = linkView(emptyState());
  assert.deepEqual(view.badge, { kind: 'checking', label: 'Connecting…' });
  assert.equal(view.showDetails, false);
});

test('a live server shows its details and the host in the badge', () => {
  const view = linkView(loaded({ running: true }));
  assert.deepEqual(view.badge, { kind: 'ok', label: 'a:38471' });
  assert.equal(view.showDetails, true);
  assert.equal(view.url, 'http://a:38471');
  assert.equal(view.interval, '6h');
  assert.equal(view.retention, '5 archives');
  assert.equal(view.running, true);
  assert.equal(view.degraded, false);
});

test('a degraded server looks connected but says live updates are unavailable', () => {
  const view = linkView(loaded({ link: 'DEGRADED' }));
  assert.equal(view.badge.kind, 'degraded');
  assert.equal(view.showDetails, true);
  assert.equal(view.degraded, true);
});

test('a live server with no health yet shows placeholders and idle', () => {
  const view = linkView(loaded({ health: null, running: null }));
  assert.equal(view.interval, '—');
  assert.equal(view.retention, '—');
  assert.equal(view.running, false);
});

test('a down server names itself and is not shown as connected', () => {
  const view = linkView(loaded({ link: 'DOWN', running: null }));
  assert.deepEqual(view.badge, { kind: 'down', label: 'Unreachable' });
  assert.equal(view.showDetails, false);
  assert.equal(view.message, 'http://a:38471 is not responding.');
});

test('no active server reads as not connected', () => {
  const view = linkView(loaded({ activeServer: null, link: 'DOWN' }));
  assert.deepEqual(view.badge, { kind: '', label: 'Not Connected' });
  assert.equal(view.message, 'No server connected.');
});

test('an unreachable client is never reported as a missing server', () => {
  for (const state of [reduce(loaded(), 'client', { reachable: false }), reduce(emptyState(), 'client', { reachable: false })]) {
    const view = linkView(state);
    assert.deepEqual(view.badge, { kind: 'down', label: 'Client unreachable' });
    assert.equal(view.showDetails, false);
    assert.ok(!view.message.includes('No server connected'));
  }
});

const plan = (overrides) => planKnown({
  shown: ['http://a:38471'],
  knownServers: ['http://a:38471'],
  activeServer: 'http://a:38471',
  selected: 'http://a:38471',
  focused: false,
  switched: false,
  ...overrides,
});

test('an unchanged list is left alone', () => {
  assert.deepEqual(plan({}), { action: 'keep' });
});

test('an empty list shows the placeholder once', () => {
  assert.deepEqual(plan({ shown: [], knownServers: [], activeServer: null, selected: '' }), { action: 'render', values: [''], selected: null });
  assert.deepEqual(plan({ shown: [''], knownServers: [], activeServer: null, selected: '' }), { action: 'keep' });
});

test('a changed list keeps the selection the user is on', () => {
  const result = plan({ knownServers: ['http://a:38471', 'http://b:38471'], selected: 'http://b:38471' });
  assert.deepEqual(result, { action: 'render', values: ['http://a:38471', 'http://b:38471'], selected: 'http://b:38471' });
});

test('a changed list falls back to the active server when the selection vanished', () => {
  const result = plan({ shown: ['http://a:38471', 'http://c:38471'], selected: 'http://c:38471' });
  assert.deepEqual(result, { action: 'render', values: ['http://a:38471'], selected: 'http://a:38471' });
});

test('a changed list is deferred while the select has focus', () => {
  assert.deepEqual(plan({ knownServers: ['http://a:38471', 'http://b:38471'], focused: true }), { action: 'defer' });
  assert.deepEqual(plan({ focused: true }), { action: 'keep' });
});

test('a switch of the active server selects it even with focus or an unchanged list', () => {
  const result = plan({ shown: ['http://a:38471', 'http://b:38471'], knownServers: ['http://a:38471', 'http://b:38471'], activeServer: 'http://b:38471', selected: 'http://a:38471', focused: true, switched: true });
  assert.deepEqual(result, { action: 'render', values: ['http://a:38471', 'http://b:38471'], selected: 'http://b:38471' });
});

test('versions are classified by update and check state', () => {
  assert.equal(classifyVersion({ updateAvailable: true, checkState: 'OK' }), 'outdated');
  assert.equal(classifyVersion({ checkState: 'NOT_CHECKED' }), 'checking');
  assert.equal(classifyVersion({ checkState: 'OK' }), 'current');
  assert.equal(classifyVersion({ checkState: 'NO_RELEASES' }), 'current');
  assert.equal(classifyVersion({ checkState: 'ERROR' }), 'unknown');
  assert.equal(classifyVersion({ checkState: 'UNAVAILABLE' }), 'unknown');
  assert.equal(classifyVersion(null), 'unknown');
});

test('the versions panel renders from the pushed versions', () => {
  const view = versionsView({
    client: { currentVersion: '0.1.0', latestVersion: '0.2.0', updateAvailable: true, checkState: 'OK', releaseUrl: 'https://example.test/r' },
    server: null,
  });
  assert.deepEqual(view.client, { kind: 'outdated', label: 'Update available: v0.2.0', version: 'v0.1.0', href: 'https://example.test/r' });
  assert.deepEqual(view.server, { kind: 'unknown', label: 'No server connected', version: '—', href: null });
});

test('only http release links are rendered', () => {
  const view = versionsView({ client: { currentVersion: '0.1.0', latestVersion: '0.2.0', updateAvailable: true, checkState: 'OK', releaseUrl: 'javascript:alert(1)' }, server: null });
  assert.equal(view.client.href, null);
});

test('versions that were never delivered read as unable to check', () => {
  assert.equal(versionsView(null).server.label, 'Unable to check');
  assert.equal(versionsView(null).client.label, 'Unable to check');
});

test('a server that has not answered yet is connecting, not unreachable', () => {
  const view = linkView(loaded({ link: 'CONNECTING', running: null, health: null }));
  assert.deepEqual(view.badge, { kind: 'checking', label: 'Connecting…' });
  assert.equal(view.showDetails, false);
  assert.equal(view.message, 'Connecting to http://a:38471…');
});

test('a reachable server that is not paired says pairing is required', () => {
  const view = linkView(loaded({ pairing: 'REQUIRED', link: 'DEGRADED' }));
  assert.deepEqual(view.badge, { kind: 'down', label: 'Pairing required' });
  assert.equal(view.showDetails, false);
  assert.match(view.message, /not paired with http:\/\/a:38471/);
});

test('a rejected token is reported as such', () => {
  const view = linkView(loaded({ pairing: 'REJECTED', link: 'DEGRADED' }));
  assert.match(view.message, /rejected this client's token/);
});

test('an unreachable server is reported as unreachable before pairing is mentioned', () => {
  const view = linkView(loaded({ pairing: 'REQUIRED', link: 'DOWN' }));
  assert.equal(view.badge.label, 'Unreachable');
});

test('a listing error only suggests connecting when no server is connected', () => {
  const error = new Error('The server rejected this client\'s token, pair it again');
  assert.equal(listingError(error, true), 'The server rejected this client\'s token, pair it again.');
  assert.equal(listingError(error, false), 'The server rejected this client\'s token, pair it again. Connect to a server to view archives.');
});

test('a message that already ends in punctuation gets no second full stop', () => {
  const error = new Error('The client or a proxy in front of it did not respond in time.');
  assert.equal(listingError(error, true), 'The client or a proxy in front of it did not respond in time.');
  assert.equal(listingError(error, false), 'The client or a proxy in front of it did not respond in time. Connect to a server to view archives.');
  assert.match(configUnavailableMessage(error), /in time\. Connect to a MediaGarrd server/);
});

test('the server configuration message tells an unpaired client to pair and otherwise keeps the reason', () => {
  const unpaired = Object.assign(new Error('This client is not paired with the server, pair it first'), { pairing: 'required' });
  assert.equal(configUnavailableMessage(unpaired), 'Pair this client with the server on the Dashboard to manage its configuration.');
  assert.match(configUnavailableMessage(new Error('No active MediaGarrd server selected')), /^No active MediaGarrd server selected\. Connect to a MediaGarrd server/);
});

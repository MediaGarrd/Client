import assert from 'node:assert/strict';
import { test } from 'node:test';
import { applyEvent, canLoadBackups, changes, emptyState, reduce } from '../../main/resources/static/js/dashboard-model.js';

const snapshot = (overrides = {}) => ({
  activeServer: 'http://a:38471',
  knownServers: ['http://a:38471'],
  link: 'LIVE',
  running: false,
  health: { backupInterval: 'PT6H', backupRetentionCount: 5 },
  versions: { client: { checkState: 'OK' }, server: { checkState: 'OK' } },
  ...overrides,
});

const loaded = (overrides) => reduce(emptyState(), 'snapshot', snapshot(overrides));

test('a snapshot replaces the whole state and marks it loaded', () => {
  const state = loaded();
  assert.equal(state.loaded, true);
  assert.equal(state.activeServer, 'http://a:38471');
  assert.deepEqual(state.knownServers, ['http://a:38471']);
  assert.equal(state.link, 'LIVE');
  assert.equal(state.running, false);
  assert.deepEqual(state.health, { backupInterval: 'PT6H', backupRetentionCount: 5 });
  assert.equal(state.clientReachable, true);
});

test('a snapshot normalizes a missing server and unknown run state', () => {
  const state = loaded({ activeServer: null, knownServers: undefined, running: null, health: null, versions: null });
  assert.equal(state.activeServer, null);
  assert.deepEqual(state.knownServers, []);
  assert.equal(state.running, null);
  assert.equal(state.health, null);
  assert.equal(state.versions, null);
});

test('events before the first snapshot are ignored', () => {
  const state = emptyState();
  assert.equal(reduce(state, 'state', { running: true }), state);
  assert.equal(reduce(state, 'link', { state: 'LIVE' }), state);
});

test('run events set the running flag', () => {
  let state = loaded();
  state = reduce(state, 'run-started', { running: true, startedAt: 't' });
  assert.equal(state.running, true);
  state = reduce(state, 'run-finished', { running: false, status: 'COMPLETED' });
  assert.equal(state.running, false);
  state = reduce(state, 'state', { running: true });
  assert.equal(state.running, true);
});

test('a link event changes the link and a DOWN link forgets the run state', () => {
  let state = loaded({ running: true });
  state = reduce(state, 'link', { server: 'http://a:38471', state: 'DEGRADED' });
  assert.equal(state.link, 'DEGRADED');
  assert.equal(state.running, true);
  state = reduce(state, 'link', { server: 'http://a:38471', state: 'DOWN' });
  assert.equal(state.link, 'DOWN');
  assert.equal(state.running, null);
});

test('servers, config and versions events update their slices only', () => {
  let state = loaded();
  state = reduce(state, 'servers', { knownServers: ['http://a:38471', 'http://b:38471'], activeServer: 'http://a:38471' });
  assert.deepEqual(state.knownServers, ['http://a:38471', 'http://b:38471']);
  assert.equal(state.link, 'LIVE');
  state = reduce(state, 'config', { backupInterval: 'PT2H', backupRetentionCount: 9 });
  assert.deepEqual(state.health, { backupInterval: 'PT2H', backupRetentionCount: 9 });
  state = reduce(state, 'versions', { client: null, server: { checkState: 'UNAVAILABLE' } });
  assert.equal(state.versions.server.checkState, 'UNAVAILABLE');
  assert.equal(state.activeServer, 'http://a:38471');
});

test('pings, backup announcements and unknown events leave the state alone', () => {
  const state = loaded();
  assert.equal(reduce(state, 'ping', {}), state);
  assert.equal(reduce(state, 'backups-changed', { at: 't' }), state);
  assert.equal(reduce(state, 'surprise', {}), state);
});

test('the client being unreachable is tracked and cleared by any event from the stream', () => {
  let state = loaded();
  state = reduce(state, 'client', { reachable: false });
  assert.equal(state.clientReachable, false);
  assert.equal(reduce(state, 'client', { reachable: false }), state);
  state = reduce(state, 'ping', {});
  assert.equal(state.clientReachable, true);
  state = reduce(reduce(state, 'client', { reachable: false }), 'snapshot', snapshot());
  assert.equal(state.clientReachable, true);
});

test('nothing happens before the first snapshot', () => {
  assert.deepEqual(changes(emptyState(), emptyState(), 'client'), { serverChanged: false, clearBackups: false, refreshBackups: false });
});

test('the first snapshot of a reachable server loads the backups once', () => {
  const next = loaded();
  assert.deepEqual(changes(emptyState(), next, 'snapshot'), { serverChanged: false, clearBackups: false, refreshBackups: true });
});

test('the first snapshot of a server that is down waits for it to come up', () => {
  const down = loaded({ link: 'DOWN' });
  assert.equal(changes(emptyState(), down, 'snapshot').refreshBackups, false);
  const up = reduce(down, 'link', { state: 'DEGRADED' });
  assert.equal(changes(down, up, 'link').refreshBackups, true);
});

test('a first snapshot without a server loads no backups', () => {
  assert.equal(changes(emptyState(), loaded({ activeServer: null, link: 'DOWN' }), 'snapshot').refreshBackups, false);
});

test('switching servers clears the table and reloads it only once the new server is up', () => {
  const before = loaded();
  const switched = reduce(before, 'snapshot', snapshot({ activeServer: 'http://b:38471', link: 'DOWN', running: null, health: null }));
  assert.deepEqual(changes(before, switched, 'snapshot'), { serverChanged: true, clearBackups: true, refreshBackups: false });
  const up = reduce(switched, 'link', { state: 'LIVE' });
  assert.deepEqual(changes(switched, up, 'link'), { serverChanged: false, clearBackups: false, refreshBackups: true });
});

test('a switch noticed through an already-up snapshot reloads right away', () => {
  const before = loaded();
  const switched = reduce(before, 'snapshot', snapshot({ activeServer: 'http://b:38471' }));
  assert.deepEqual(changes(before, switched, 'snapshot'), { serverChanged: true, clearBackups: true, refreshBackups: true });
});

test('clearing the server clears the table without reloading it', () => {
  const before = loaded();
  const cleared = reduce(before, 'snapshot', snapshot({ activeServer: null, link: 'DOWN', running: null }));
  assert.deepEqual(changes(before, cleared, 'snapshot'), { serverChanged: true, clearBackups: true, refreshBackups: false });
});

test('a run finishing reloads the backups, however it was learned', () => {
  const running = loaded({ running: true });
  const viaEvent = reduce(running, 'run-finished', { running: false });
  assert.equal(changes(running, viaEvent, 'run-finished').refreshBackups, true);
  const viaState = reduce(running, 'state', { running: false });
  assert.equal(changes(running, viaState, 'state').refreshBackups, true);
  const idle = loaded();
  assert.equal(changes(idle, reduce(idle, 'state', { running: false }), 'state').refreshBackups, false);
  assert.equal(changes(idle, reduce(idle, 'run-started', {}), 'run-started').refreshBackups, false);
});

test('a backups-changed announcement reloads the backups while the server is up', () => {
  const state = loaded();
  assert.equal(changes(state, reduce(state, 'backups-changed', {}), 'backups-changed').refreshBackups, true);
  const down = loaded({ link: 'DOWN' });
  assert.equal(changes(down, reduce(down, 'backups-changed', {}), 'backups-changed').refreshBackups, false);
});

test('coming back from DOWN or from an unreachable client reloads the backups', () => {
  const down = loaded({ link: 'DOWN' });
  assert.equal(changes(down, reduce(down, 'link', { state: 'LIVE' }), 'link').refreshBackups, true);
  const live = loaded();
  const unreachable = reduce(live, 'client', { reachable: false });
  assert.equal(changes(live, unreachable, 'client').refreshBackups, false);
  assert.equal(changes(unreachable, reduce(unreachable, 'client', { reachable: true }), 'client').refreshBackups, true);
});

test('a degraded to live move does not reload anything', () => {
  const degraded = loaded({ link: 'DEGRADED' });
  assert.equal(changes(degraded, reduce(degraded, 'link', { state: 'LIVE' }), 'link').refreshBackups, false);
});

test('config, versions and server list updates never reload the backups', () => {
  const state = loaded();
  assert.equal(changes(state, reduce(state, 'config', { backupInterval: 'PT1H', backupRetentionCount: 2 }), 'config').refreshBackups, false);
  assert.equal(changes(state, reduce(state, 'versions', {}), 'versions').refreshBackups, false);
  assert.equal(changes(state, reduce(state, 'servers', { knownServers: [], activeServer: 'http://a:38471' }), 'servers').refreshBackups, false);
});

test('a backups-changed announcement is an effect even though it leaves the state identical', () => {
  const state = loaded();
  const result = applyEvent(state, 'backups-changed', { at: 't' });
  assert.equal(result.state, state);
  assert.deepEqual(result.effects, { serverChanged: false, clearBackups: false, refreshBackups: true });
});

test('a backups-changed announcement does nothing while the server is not up', () => {
  for (const link of ['DOWN', 'CONNECTING']) {
    const state = loaded({ link });
    assert.equal(applyEvent(state, 'backups-changed', { at: 't' }).effects.refreshBackups, false);
  }
});

test('backups may be loaded only for a reachable client with an active server whose link is up', () => {
  assert.equal(canLoadBackups(emptyState()), false);
  assert.equal(canLoadBackups(loaded()), true);
  assert.equal(canLoadBackups(loaded({ link: 'DEGRADED' })), true);
  assert.equal(canLoadBackups(loaded({ link: 'DOWN' })), false);
  assert.equal(canLoadBackups(loaded({ link: 'CONNECTING' })), false);
  assert.equal(canLoadBackups(loaded({ activeServer: null })), false);
  assert.equal(canLoadBackups(reduce(loaded(), 'client', { reachable: false })), false);
});

test('a connecting link forgets the run state and a first answer reloads the backups', () => {
  const live = loaded({ running: true });
  const connecting = reduce(live, 'link', { state: 'CONNECTING' });
  assert.equal(connecting.running, null);
  const up = reduce(connecting, 'link', { state: 'DEGRADED' });
  assert.equal(changes(connecting, up, 'link').refreshBackups, true);
});

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { emptyState, reduce } from '../../main/resources/static/js/dashboard-model.js';
import { codeView, credentialProblem, pairingNote, pairingPanel } from '../../main/resources/static/js/pairing-model.js';

const loaded = (overrides = {}) => reduce(emptyState(), 'snapshot', {
  activeServer: 'https://garrd.example.com',
  knownServers: ['https://garrd.example.com'],
  link: 'DEGRADED',
  running: null,
  health: null,
  versions: null,
  pairing: 'PAIRED',
  ...overrides,
});

test('the pairing panel is hidden until a reachable server is selected', () => {
  assert.equal(pairingPanel(emptyState()), 'hidden');
  assert.equal(pairingPanel(loaded({ activeServer: null, pairing: 'NONE' })), 'hidden');
  assert.equal(pairingPanel(loaded({ link: 'DOWN' })), 'hidden');
  assert.equal(pairingPanel(loaded({ link: 'CONNECTING', pairing: 'REQUIRED' })), 'hidden');
});

test('an unpaired or rejected server asks for pairing and a paired one offers codes and unpairing', () => {
  assert.equal(pairingPanel(loaded({ pairing: 'REQUIRED' })), 'pair');
  assert.equal(pairingPanel(loaded({ pairing: 'REJECTED' })), 'pair');
  assert.equal(pairingPanel(loaded({ pairing: 'PAIRED', link: 'LIVE' })), 'manage');
});

test('a rejected token is explained differently from a missing one', () => {
  const rejected = pairingNote(loaded({ pairing: 'REJECTED' }));
  assert.match(rejected, /no longer accepts/);
  assert.match(rejected, /^The server no longer accepts this client\. Enter a pairing code from a client that is still paired\./);
  assert.match(rejected, /Pairing PIN.*only when no client is paired.*MEDIAGARRD_OPEN_PAIRING/);
  assert.match(pairingNote(loaded({ pairing: 'REQUIRED' })), /Pairing PIN/);
});

test('an empty credential is refused before it is sent', () => {
  assert.ok(credentialProblem('   '));
  assert.ok(credentialProblem(undefined));
  assert.equal(credentialProblem(' 123456 '), null);
});

test('a pairing code counts down and then reads as expired', () => {
  const code = { code: 'ABCDE-FGHJK', expiresAt: '2026-11-19T12:10:00Z' };
  const start = Date.parse('2026-11-19T12:00:00Z');
  assert.deepEqual(codeView(code, start), { text: 'ABCDE-FGHJK', expires: 'Expires in 10:00' });
  assert.equal(codeView(code, start + 541_000).expires, 'Expires in 0:59');
  assert.equal(codeView(code, start + 600_000).expires, 'Expired');
  assert.equal(codeView(null, start), null);
});

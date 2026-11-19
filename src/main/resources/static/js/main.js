import { initAuth } from './auth.js';
import { initBackups } from './backups.js';
import { initConnection } from './connection.js';
import { applyEvent, canLoadBackups, emptyState, needsPairing } from './dashboard-model.js';
import { initEvents } from './events.js';
import { api, loginRequired, onPairingRequired } from './api.js';
import { createNotifier } from './notify.js';
import { initPairing } from './pairing.js';
import { initPickupInterval } from './pickup-interval.js';
import { initServerConfig } from './server-config.js';
import { initViews } from './views.js';
import { initVersions } from './versions.js';

const DASHBOARD_REFRESH_MS = 60000;

const notify = createNotifier(document.getElementById('banner'));
const pickupInterval = initPickupInterval(document.getElementById('pickupIntervalForm'), notify);
const auth = initAuth({
  root: document.getElementById('auth'),
  logoutButton: document.querySelector('[data-logout]'),
  background: document.querySelectorAll('.topbar, .page'),
});
const pairing = initPairing(document.getElementById('pairing'), notify, { resync: () => resync() });
const serverConfig = initServerConfig(document.querySelector('[data-view="config"]'), notify);
const versions = initVersions(document.getElementById('versions'));
const backups = initBackups({
  root: document.getElementById('backups'),
  actions: document.getElementById('actions'),
}, notify);
const connection = initConnection({
  root: document.getElementById('connection'),
  statusPanel: document.getElementById('serverStatus'),
  badge: document.getElementById('connBadge'),
}, notify, { resync: () => resync() });

let state = emptyState();
let pushes = 0;
let pickupLoad = 'idle';
let snapshots = 0;
let backupsAvailable = false;

function loadPickupInterval() {
  if (pickupLoad !== 'idle') return;
  pickupLoad = 'loading';
  api.status()
    .then((status) => {
      pickupInterval.show(status.pickupInterval);
      pickupLoad = 'done';
    })
    .catch(() => { pickupLoad = 'idle'; });
}

function dispatch(name, payload) {
  const { state: next, effects } = applyEvent(state, name, payload);
  const unchanged = next === state;
  if (unchanged && !effects.refreshBackups) return;
  state = next;
  if (!unchanged) {
    connection.render(state);
    pairing.render(state);
    backups.setServerRunning(state.running === true);
    syncBackupsAvailability();
    if (state.loaded) versions.render(state.versions);
  }
  if (effects.clearBackups) backups.reset();
  if (effects.serverChanged) serverConfig.serverChanged();
  if (effects.refreshBackups) backups.refresh();
  if (state.loaded && state.clientReachable) loadPickupInterval();
}

function syncBackupsAvailability() {
  const available = canLoadBackups(state);
  backups.setPairingNeeded(needsPairing(state) && state.activeServer !== null);
  if (backupsAvailable && !available) {
    if (needsPairing(state)) backups.reset();
    else backups.clear();
  }
  backupsAvailable = available;
  backups.setAvailable(available);
}

async function resync() {
  const startedAt = pushes;
  const mine = ++snapshots;
  try {
    const snapshot = await api.dashboard();
    if (pushes === startedAt && mine === snapshots) dispatch('snapshot', snapshot);
  } catch (error) {
    if (error instanceof TypeError && pushes === startedAt && mine === snapshots) dispatch('client', { reachable: false });
  }
}

onPairingRequired(() => resync());

async function checkSession() {
  const status = await api.authStatus();
  if (status && status.authenticated === false) loginRequired();
}

function refreshBackupsIfUp() {
  if (canLoadBackups(state)) backups.refresh();
}

document.addEventListener('click', (event) => {
  const button = event.target.closest('button');
  if (!button) return;
  button.classList.add('clicked');
  setTimeout(() => button.classList.remove('clicked'), 140);
});

function start() {
  initEvents({
    onEvent: (name, payload) => {
      pushes++;
      dispatch(name, payload);
    },
    onConnection: (reachable) => dispatch('client', { reachable }),
    checkSession,
  });

  let viewShown = false;
  initViews((view) => {
    notify.clear();
    if (view === 'config') serverConfig.load();
    else if (viewShown) {
      resync();
      refreshBackupsIfUp();
    }
    viewShown = true;
  });

  loadPickupInterval();
  resync();

  setInterval(() => {
    if (document.hidden) return;
    resync();
    if (location.hash === '#config') return;
    backups.rerender();
  }, DASHBOARD_REFRESH_MS);

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) return;
    resync();
    if (location.hash !== '#config') refreshBackupsIfUp();
  });
}

if (await auth.ensureSignedIn()) start();

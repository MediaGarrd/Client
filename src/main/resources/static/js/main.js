import { initBackups } from './backups.js';
import { initConnection } from './connection.js';
import { applyEvent, canLoadBackups, emptyState } from './dashboard-model.js';
import { initEvents } from './events.js';
import { api } from './api.js';
import { createNotifier } from './notify.js';
import { initPickupInterval } from './pickup-interval.js';
import { initServerConfig } from './server-config.js';
import { initViews } from './views.js';
import { initVersions } from './versions.js';

const DASHBOARD_REFRESH_MS = 60000;

const notify = createNotifier(document.getElementById('banner'));
const pickupInterval = initPickupInterval(document.getElementById('pickupIntervalForm'), notify);
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
    backups.setServerRunning(state.running === true);
    if (state.loaded) versions.render(state.versions);
  }
  if (effects.clearBackups) backups.clear();
  if (effects.serverChanged) serverConfig.serverChanged();
  if (effects.refreshBackups) backups.refresh();
  if (state.loaded && state.clientReachable) loadPickupInterval();
}

async function resync() {
  const startedAt = pushes;
  try {
    const snapshot = await api.dashboard();
    if (pushes === startedAt) dispatch('snapshot', snapshot);
  } catch (error) {
    if (error instanceof TypeError && pushes === startedAt) dispatch('client', { reachable: false });
  }
}

function refreshBackupsIfUp() {
  if (canLoadBackups(state)) backups.refresh();
}

initEvents({
  onEvent: (name, payload) => {
    pushes++;
    dispatch(name, payload);
  },
  onConnection: (reachable) => dispatch('client', { reachable }),
});

document.addEventListener('click', (event) => {
  const button = event.target.closest('button');
  if (!button) return;
  button.classList.add('clicked');
  setTimeout(() => button.classList.remove('clicked'), 140);
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

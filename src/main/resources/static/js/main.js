import { initBackups } from './backups.js';
import { initConnection } from './connection.js';
import { createNotifier } from './notify.js';
import { initPickupInterval } from './pickup-interval.js';
import { initServerConfig } from './server-config.js';
import { initViews } from './views.js';

const HEALTH_INTERVAL_MS = 7000;

const notify = createNotifier(document.getElementById('banner'));
const pickupInterval = initPickupInterval(document.getElementById('pickupIntervalForm'), notify);
const serverConfig = initServerConfig(document.querySelector('[data-view="config"]'), notify);
const backups = initBackups({
  root: document.getElementById('backups'),
  actions: document.getElementById('actions'),
}, notify, { refreshHealth: () => connection.refreshHealth() });

const connection = initConnection({
  root: document.getElementById('connection'),
  statusPanel: document.getElementById('serverStatus'),
  badge: document.getElementById('connBadge'),
}, notify, {
  onServerChanged: () => {
    backups.refresh();
    serverConfig.serverChanged();
  },
  onHealth: (health) => backups.setServerRunning(Boolean(health.serverStatus?.running)),
  onStatus: (status) => pickupInterval.show(status.pickupInterval),
});

document.addEventListener('click', (event) => {
  const button = event.target.closest('button');
  if (!button) return;
  button.classList.add('clicked');
  setTimeout(() => button.classList.remove('clicked'), 140);
});

initViews((view) => {
  notify.clear();
  if (view === 'config') serverConfig.load();
  else connection.refreshHealth();
});

connection.refreshStatus().catch(notify.error).then(backups.refresh);
setInterval(() => {
  if (!document.hidden) connection.refreshHealth();
}, HEALTH_INTERVAL_MS);
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) connection.refreshHealth();
});

import { api } from './api.js';
import { formatInterval } from './format.js';

export function initConnection({ root, statusPanel, badge }, notify, { onServerChanged, onHealth, onStatus }) {
  const ipForm = root.querySelector('[data-ip-form]');
  const known = root.querySelector('[data-known]');
  const controls = root.querySelectorAll('button, input, select');
  const scanBar = root.querySelector('[data-scan-bar]');
  const scanNote = root.querySelector('[data-scan-note]');
  const details = statusPanel.querySelector('[data-details]');
  const noServer = statusPanel.querySelector('[data-no-server]');
  const state = statusPanel.querySelector('[data-state]');
  let busy = false;
  let connected = null;
  let healthRequest = null;

  function setBadge(kind, label) {
    badge.className = `conn-badge ${kind}`;
    badge.querySelector('[data-label]').textContent = label;
  }

  function renderHealth(health) {
    details.hidden = !health.connected;
    noServer.hidden = health.connected;
    if (!health.connected) {
      noServer.textContent = health.activeServer ? `${health.activeServer} is not responding.` : 'No server connected.';
      if (!busy) setBadge(health.activeServer ? 'down' : '', health.activeServer ? 'Unreachable' : 'Not Connected');
      return;
    }
    const server = health.serverStatus;
    statusPanel.querySelector('[data-url]').textContent = health.activeServer;
    statusPanel.querySelector('[data-interval]').textContent = server ? formatInterval(server.backupInterval) : '—';
    statusPanel.querySelector('[data-retention]').textContent = server ? `${server.backupRetentionCount} archives` : '—';
    state.textContent = server?.running ? 'Running' : 'Idle';
    state.classList.toggle('running', Boolean(server?.running));
    if (!busy) setBadge('ok', health.activeServer.replace(/^https?:\/\//, ''));
  }

  function refreshHealth() {
    healthRequest ??= api.serverHealth()
      .catch(() => ({ connected: false }))
      .then((health) => {
        const reconnected = health.connected && connected === false;
        connected = health.connected;
        renderHealth(health);
        onHealth(health);
        if (reconnected) onServerChanged();
      })
      .finally(() => { healthRequest = null; });
    return healthRequest;
  }

  function renderKnown(status) {
    const servers = status.knownServers || [];
    const options = servers.map((server) => new Option(server, server, false, server === status.activeServer));
    known.replaceChildren(...(options.length ? options : [new Option('— none discovered yet —', '')]));
  }

  async function refreshStatus() {
    const status = await api.status();
    renderKnown(status);
    onStatus(status);
    await healthRequest;
    await refreshHealth();
  }

  async function connect(label, action) {
    if (busy) return null;
    notify.clear();
    busy = true;
    setBadge('checking', label);
    scanBar.classList.add('active');
    controls.forEach((control) => { control.disabled = true; });
    let result = null;
    try {
      result = await action();
    } catch (error) {
      notify.error(error);
    }
    busy = false;
    scanBar.classList.remove('active');
    controls.forEach((control) => { control.disabled = false; });
    connected = null;
    await refreshStatus().catch(notify.error);
    if (result) onServerChanged();
    return result;
  }

  ipForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const { ip, port } = ipForm.elements;
    connect('Probing…', () => api.selectServer({
      serverIp: ip.value.trim(),
      serverPort: port.value ? Number(port.value) : null,
    }));
  });

  root.querySelector('[data-connect-known]').addEventListener('click', () => {
    if (!known.value) {
      notify.error('No server selected.');
      return;
    }
    connect('Connecting…', () => api.selectServer({ serverUrl: known.value }));
  });

  root.querySelector('[data-discover]').addEventListener('click', async () => {
    if (busy) return;
    scanNote.textContent = 'Scan in progress…';
    const found = await connect('Scanning…', api.discover);
    const count = found?.length ?? 0;
    scanNote.textContent = found ? `Last scan: found ${count} server${count === 1 ? '' : 's'}.` : 'Last scan: failed.';
  });

  return { refreshStatus, refreshHealth };
}

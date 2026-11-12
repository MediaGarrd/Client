import { api } from './api.js';
import { linkView, planKnown } from './view-model.js';

export function initConnection({ root, statusPanel, badge }, notify, { resync }) {
  const ipForm = root.querySelector('[data-ip-form]');
  const known = root.querySelector('[data-known]');
  const controls = root.querySelectorAll('button, input, select');
  const scanBar = root.querySelector('[data-scan-bar]');
  const scanNote = root.querySelector('[data-scan-note]');
  const details = statusPanel.querySelector('[data-details]');
  const noServer = statusPanel.querySelector('[data-no-server]');
  const degraded = statusPanel.querySelector('[data-degraded]');
  const runState = statusPanel.querySelector('[data-state]');
  let busy = false;
  let latest = null;
  let renderedFor;
  let deferred = false;

  function setBadge(kind, label) {
    badge.className = `conn-badge ${kind}`;
    badge.querySelector('[data-label]').textContent = label;
  }

  function renderKnown(state) {
    const plan = planKnown({
      shown: [...known.options].map((option) => option.value),
      knownServers: state.knownServers,
      activeServer: state.activeServer,
      selected: known.value,
      focused: document.activeElement === known,
      switched: renderedFor !== undefined && renderedFor !== state.activeServer,
    });
    deferred = plan.action === 'defer';
    if (plan.action === 'defer') return;
    renderedFor = state.activeServer;
    if (plan.action === 'keep') return;
    const options = plan.values[0] === ''
      ? [new Option('— none discovered yet —', '')]
      : plan.values.map((server) => new Option(server, server, false, server === plan.selected));
    known.replaceChildren(...options);
  }

  function render(state) {
    latest = state;
    const view = linkView(state);
    details.hidden = !view.showDetails;
    noServer.hidden = view.showDetails;
    noServer.textContent = view.message;
    degraded.hidden = !view.degraded;
    if (view.showDetails) {
      statusPanel.querySelector('[data-url]').textContent = view.url;
      statusPanel.querySelector('[data-interval]').textContent = view.interval;
      statusPanel.querySelector('[data-retention]').textContent = view.retention;
      runState.textContent = view.running ? 'Running' : 'Idle';
      runState.classList.toggle('running', view.running);
    }
    if (!busy) setBadge(view.badge.kind, view.badge.label);
    if (state.loaded) renderKnown(state);
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
    if (latest) render(latest);
    await resync();
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

  known.addEventListener('blur', () => {
    if (deferred && latest) renderKnown(latest);
  });

  return { render };
}

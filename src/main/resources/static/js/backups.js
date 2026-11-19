import { api } from './api.js';
import { bytes, percent, relativeTime } from './format.js';
import { listingError } from './view-model.js';

const POLL_INTERVAL_MS = 350;
const POLL_MAX_INTERVAL_MS = 1500;
const EMPTY_DEFAULT = 'No backup archives found on server.';
const EMPTY_DISCONNECTED = 'No backups loaded. Connect to a server to view archives.';
const EMPTY_UNPAIRED = 'No backups loaded. Pair this client with the server to view archives.';

export function initBackups({ root, actions }, notify) {
  const rows = root.querySelector('tbody');
  const empty = root.querySelector('[data-empty]');
  const headers = root.querySelectorAll('th[data-sort-key]');
  const template = root.querySelector('#backupTemplate');
  const runButton = actions.querySelector('[data-run]');
  const pickupButton = actions.querySelector('[data-pickup]');
  const sort = { key: 'fileName', dir: 'asc' };
  const rowTasks = new Map();
  const rowButtons = new Map();
  let backups = [];
  let serverRunning = false;
  let runInFlight = false;
  let available = false;
  let latestTask = null;
  let pollTimer = null;
  let pollDelay = POLL_INTERVAL_MS;
  let latestRequest = 0;
  let generation = 0;
  let pairingNeeded = false;
  let emptyMessage = EMPTY_DISCONNECTED;

  function renderActions() {
    const backupBusy = serverRunning || runInFlight;
    runButton.disabled = backupBusy || !available;
    runButton.classList.toggle('busy', backupBusy);
    runButton.querySelector('.btn-label').textContent = backupBusy ? 'Backup In Progress' : 'Run Backup Now';

    pickupButton.disabled = Boolean(latestTask) || !available;
    pickupButton.classList.toggle('busy', Boolean(latestTask));
    pickupButton.querySelector('.btn-label').textContent = latestTask ? 'Downloading...' : 'Download Latest';
    pickupButton.querySelector('.btn-progress-fill').style.width =
      `${latestTask ? percent(latestTask.bytesWritten, latestTask.totalBytes) : 0}%`;
  }

  function renderRowButton(id) {
    const button = rowButtons.get(id);
    if (!button) return;
    const task = rowTasks.get(id);
    button.disabled = Boolean(task) || !available;
    if (!task) {
      button.textContent = '↓';
      return;
    }
    const progress = document.createElement('span');
    progress.className = 'row-progress-circle';
    progress.style.setProperty('--p', `${percent(task.bytesWritten, task.totalBytes)}%`);
    button.replaceChildren(progress);
  }

  function compare(a, b) {
    const dir = sort.dir === 'asc' ? 1 : -1;
    if (sort.key === 'sizeBytes') return ((a.sizeBytes ?? 0) - (b.sizeBytes ?? 0)) * dir;
    if (sort.key === 'createdAt') return (new Date(a.createdAt) - new Date(b.createdAt)) * dir;
    return String(a[sort.key] ?? '').localeCompare(String(b[sort.key] ?? ''), undefined, { sensitivity: 'base' }) * dir;
  }

  function renderRow(backup) {
    const row = template.content.firstElementChild.cloneNode(true);
    row.querySelector('[data-file]').textContent = backup.fileName;
    row.querySelector('[data-size]').textContent = bytes(backup.sizeBytes);
    row.querySelector('[data-created]').textContent = relativeTime(backup.createdAt);
    const id = row.querySelector('[data-id]');
    id.textContent = backup.id.length > 22 ? `${backup.id.slice(0, 22)}…` : backup.id;
    id.title = backup.id;
    const button = row.querySelector('button');
    button.addEventListener('click', () => pickupById(backup.id));
    rowButtons.set(backup.id, button);
    renderRowButton(backup.id);
    return row;
  }

  function renderTable(message = emptyMessage) {
    emptyMessage = message;
    headers.forEach((header) => {
      const active = header.dataset.sortKey === sort.key;
      header.classList.toggle('active', active);
      header.setAttribute('aria-sort', active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none');
      header.querySelector('.sort-indicator').textContent = active ? (sort.dir === 'asc' ? '▲' : '▼') : '↕';
    });
    rowButtons.clear();
    rows.replaceChildren(...[...backups].sort(compare).map(renderRow));
    empty.textContent = emptyMessage;
    empty.hidden = backups.length > 0;
  }

  async function refresh() {
    const request = ++latestRequest;
    try {
      const loaded = await api.listBackups() || [];
      if (request !== latestRequest) return;
      backups = loaded;
      renderTable(EMPTY_DEFAULT);
    } catch (error) {
      if (request !== latestRequest) return;
      backups = [];
      renderTable(listingError(error, available));
    }
  }

  function finish(id) {
    if (id === null) {
      latestTask = null;
      renderActions();
      refresh();
    } else {
      rowTasks.delete(id);
      renderRowButton(id);
    }
  }

  async function poll() {
    const tasks = [...rowTasks].filter(([, task]) => task.taskId).map(([id, task]) => ({ id, task }));
    if (latestTask?.taskId) tasks.push({ id: null, task: latestTask });
    if (!tasks.length) {
      pollTimer = null;
      return;
    }
    const mine = generation;
    await Promise.all(tasks.map(async ({ id, task }) => {
      try {
        const progress = await api.pickupProgress(task.taskId);
        if (mine !== generation) return;
        task.bytesWritten = progress.bytesWritten || 0;
        task.totalBytes = progress.totalBytes || task.totalBytes;
        if (progress.status === 'FAILED') notify.error(progress.error || 'Download failed');
        if (progress.status === 'COMPLETED' || progress.status === 'FAILED') finish(id);
      } catch (error) {
        if (mine !== generation) return;
        notify.error(error);
        finish(id);
      }
    }));
    if (mine !== generation) {
      pollTimer = null;
      return;
    }
    rowTasks.forEach((_, id) => renderRowButton(id));
    renderActions();
    pollDelay = Math.min(Math.round(pollDelay * 1.3), POLL_MAX_INTERVAL_MS);
    pollTimer = setTimeout(poll, pollDelay);
  }

  function startPolling() {
    if (pollTimer) return;
    pollDelay = POLL_INTERVAL_MS;
    pollTimer = setTimeout(poll, pollDelay);
  }

  function toTask(started) {
    if (!started?.taskId) throw new Error('The client did not start a download.');
    return { taskId: started.taskId, bytesWritten: 0, totalBytes: started.totalBytes || 0 };
  }

  async function pickupById(id) {
    if (rowTasks.has(id) || !available) return;
    notify.clear();
    const mine = generation;
    rowTasks.set(id, { taskId: null, bytesWritten: 0, totalBytes: 0 });
    renderRowButton(id);
    try {
      const task = toTask(await api.pickupById(id));
      if (mine !== generation) return;
      rowTasks.set(id, task);
      startPolling();
    } catch (error) {
      if (mine !== generation) return;
      rowTasks.delete(id);
      notify.error(error);
    }
    renderRowButton(id);
  }

  pickupButton.addEventListener('click', async () => {
    if (latestTask || !available) return;
    notify.clear();
    const mine = generation;
    latestTask = { taskId: null, bytesWritten: 0, totalBytes: 0 };
    renderActions();
    try {
      const task = toTask(await api.pickupLatest());
      if (mine !== generation) return;
      latestTask = task;
      startPolling();
    } catch (error) {
      if (mine !== generation) return;
      latestTask = null;
      notify.error(error);
    }
    renderActions();
  });

  runButton.addEventListener('click', async () => {
    if (serverRunning || runInFlight || !available) return;
    notify.clear();
    runInFlight = true;
    renderActions();
    try {
      await api.runBackup();
    } catch (error) {
      notify.error(error);
    } finally {
      runInFlight = false;
      renderActions();
    }
  });

  headers.forEach((header) => header.querySelector('button').addEventListener('click', () => {
    const key = header.dataset.sortKey;
    if (sort.key === key) {
      sort.dir = sort.dir === 'asc' ? 'desc' : 'asc';
    } else {
      sort.key = key;
      sort.dir = key === 'createdAt' ? 'desc' : 'asc';
    }
    renderTable();
  }));

  root.querySelector('[data-refresh]').addEventListener('click', () => {
    notify.clear();
    refresh();
  });

  renderTable(EMPTY_DISCONNECTED);
  renderActions();

  function clear() {
    latestRequest++;
    backups = [];
    renderTable(pairingNeeded ? EMPTY_UNPAIRED : EMPTY_DISCONNECTED);
  }

  return {
    refresh,
    rerender: () => renderTable(),
    clear,
    reset() {
      generation++;
      rowTasks.clear();
      latestTask = null;
      clearTimeout(pollTimer);
      pollTimer = null;
      clear();
      renderActions();
    },
    setPairingNeeded(value) {
      if (pairingNeeded === value) return;
      pairingNeeded = value;
      if (!available && backups.length === 0) renderTable(value ? EMPTY_UNPAIRED : EMPTY_DISCONNECTED);
    },
    setAvailable(value) {
      if (available === value) return;
      available = value;
      rowButtons.forEach((_, id) => renderRowButton(id));
      renderActions();
    },
    setServerRunning(running) {
      serverRunning = running;
      renderActions();
    },
  };
}

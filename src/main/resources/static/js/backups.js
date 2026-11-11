import { api } from './api.js';
import { bytes, percent, relativeTime } from './format.js';

const POLL_INTERVAL_MS = 350;

export function initBackups({ root, actions }, notify, { refreshHealth }) {
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
  let latestTask = null;
  let pollTimer = null;

  function renderActions() {
    const backupBusy = serverRunning || runInFlight;
    runButton.disabled = backupBusy;
    runButton.classList.toggle('busy', backupBusy);
    runButton.querySelector('.btn-label').textContent = backupBusy ? 'Backup In Progress' : 'Run Backup Now';

    pickupButton.disabled = Boolean(latestTask);
    pickupButton.classList.toggle('busy', Boolean(latestTask));
    pickupButton.querySelector('.btn-label').textContent = latestTask ? 'Downloading...' : 'Download Latest';
    pickupButton.querySelector('.btn-progress-fill').style.width =
      `${latestTask ? percent(latestTask.bytesWritten, latestTask.totalBytes) : 0}%`;
  }

  function renderRowButton(id) {
    const button = rowButtons.get(id);
    if (!button) return;
    const task = rowTasks.get(id);
    button.disabled = Boolean(task);
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

  function renderTable(emptyMessage = 'No backup archives found on server.') {
    headers.forEach((header) => {
      const active = header.dataset.sortKey === sort.key;
      header.classList.toggle('active', active);
      header.querySelector('.sort-indicator').textContent = active ? (sort.dir === 'asc' ? '▲' : '▼') : '↕';
    });
    rowButtons.clear();
    rows.replaceChildren(...[...backups].sort(compare).map(renderRow));
    empty.textContent = emptyMessage;
    empty.hidden = backups.length > 0;
  }

  async function refresh() {
    try {
      backups = await api.listBackups() || [];
      renderTable();
    } catch (error) {
      backups = [];
      renderTable(`${error.message}. Connect to a server to view archives.`);
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
    const tasks = [...rowTasks].map(([id, task]) => ({ id, task }));
    if (latestTask?.taskId) tasks.push({ id: null, task: latestTask });
    if (!tasks.length) {
      pollTimer = null;
      return;
    }
    await Promise.all(tasks.map(async ({ id, task }) => {
      try {
        const progress = await api.pickupProgress(task.taskId);
        task.bytesWritten = progress.bytesWritten || 0;
        task.totalBytes = progress.totalBytes || task.totalBytes;
        if (progress.status === 'FAILED') notify.error(progress.error || 'Download failed');
        if (progress.status === 'COMPLETED' || progress.status === 'FAILED') finish(id);
      } catch (error) {
        notify.error(error);
        finish(id);
      }
    }));
    rowTasks.forEach((_, id) => renderRowButton(id));
    renderActions();
    pollTimer = setTimeout(poll, POLL_INTERVAL_MS);
  }

  function startPolling() {
    if (!pollTimer) pollTimer = setTimeout(poll, POLL_INTERVAL_MS);
  }

  const toTask = (started) => ({ taskId: started.taskId, bytesWritten: 0, totalBytes: started.totalBytes || 0 });

  async function pickupById(id) {
    if (rowTasks.has(id)) return;
    notify.clear();
    try {
      rowTasks.set(id, toTask(await api.pickupById(id)));
      renderRowButton(id);
      startPolling();
    } catch (error) {
      notify.error(error);
    }
  }

  pickupButton.addEventListener('click', async () => {
    if (latestTask) return;
    notify.clear();
    latestTask = { taskId: null, bytesWritten: 0, totalBytes: 0 };
    renderActions();
    try {
      latestTask = toTask(await api.pickupLatest());
      startPolling();
    } catch (error) {
      latestTask = null;
      notify.error(error);
    }
    renderActions();
  });

  runButton.addEventListener('click', async () => {
    if (serverRunning || runInFlight) return;
    notify.clear();
    runInFlight = true;
    renderActions();
    try {
      await api.runBackup();
      await refresh();
    } catch (error) {
      notify.error(error);
    } finally {
      runInFlight = false;
      await refreshHealth();
    }
  });

  headers.forEach((header) => header.addEventListener('click', () => {
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

  renderTable('No backups loaded. Connect to a server to view archives.');
  renderActions();

  return {
    refresh,
    setServerRunning(running) {
      const finished = serverRunning && !running;
      serverRunning = running;
      renderActions();
      if (finished) refresh();
    },
  };
}

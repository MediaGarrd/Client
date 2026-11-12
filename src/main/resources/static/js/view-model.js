import { formatInterval } from './format.js';

const RELEASE_URL = /^https?:\/\//;

export function linkView(state) {
  const none = { showDetails: false, url: '', interval: '—', retention: '—', running: false, degraded: false };
  if (!state.clientReachable) {
    return { ...none, badge: { kind: 'down', label: 'Client unreachable' }, message: 'Client unreachable. Retrying…' };
  }
  if (!state.loaded) {
    return { ...none, badge: { kind: 'checking', label: 'Connecting…' }, message: 'Connecting…' };
  }
  if (!state.activeServer) {
    return { ...none, badge: { kind: '', label: 'Not Connected' }, message: 'No server connected.' };
  }
  if (state.link === 'CONNECTING') {
    return { ...none, badge: { kind: 'checking', label: 'Connecting…' }, message: `Connecting to ${state.activeServer}…` };
  }
  if (state.link === 'DOWN') {
    return { ...none, badge: { kind: 'down', label: 'Unreachable' }, message: `${state.activeServer} is not responding.` };
  }
  return {
    badge: { kind: 'ok', label: state.activeServer.replace(/^https?:\/\//, '') },
    message: '',
    showDetails: true,
    url: state.activeServer,
    interval: state.health ? formatInterval(state.health.backupInterval) : '—',
    retention: state.health ? `${state.health.backupRetentionCount} archives` : '—',
    running: state.running === true,
    degraded: state.link === 'DEGRADED',
  };
}

export function planKnown({ shown, knownServers, activeServer, selected, focused, switched }) {
  const values = knownServers.length ? knownServers : [''];
  if (!switched) {
    const unchanged = values.length === shown.length && values.every((value, index) => shown[index] === value);
    if (unchanged) return { action: 'keep' };
    if (focused) return { action: 'defer' };
  }
  const keep = !switched && knownServers.includes(selected);
  return { action: 'render', values, selected: keep ? selected : activeServer };
}

export function classifyVersion(status) {
  if (status?.updateAvailable) return 'outdated';
  if (status?.checkState === 'NOT_CHECKED') return 'checking';
  if (status?.checkState === 'OK' || status?.checkState === 'NO_RELEASES') return 'current';
  return 'unknown';
}

export function versionRowView(status, missingText) {
  const kind = classifyVersion(status);
  const label = {
    current: 'Up-to-date',
    outdated: `Update available: v${status?.latestVersion}`,
    checking: 'Checking…',
    unknown: status ? 'Unable to check' : missingText,
  }[kind];
  const showLink = kind === 'outdated' && RELEASE_URL.test(status.releaseUrl || '');
  return {
    kind,
    label,
    version: status?.currentVersion ? `v${status.currentVersion}` : '—',
    href: showLink ? status.releaseUrl : null,
  };
}

export function versionsView(versions) {
  return {
    server: versionRowView(versions?.server, versions ? 'No server connected' : 'Unable to check'),
    client: versionRowView(versions?.client, 'Unable to check'),
  };
}

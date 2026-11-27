import { needsPairing } from './dashboard-model.js';
import { formatInterval } from './format.js';

const RELEASE_URL = /^https?:\/\//;

export function listingError(error, connected) {
  const message = /[.!?]$/.test(error.message) ? error.message : `${error.message}.`;
  return connected ? message : `${message} Connect to a server to view archives.`;
}

export function configUnavailableMessage(error) {
  if (error?.pairing === 'required') {
    return 'Pair this client with the server on the Dashboard to manage its configuration.';
  }
  const message = /[.!?]$/.test(error.message) ? error.message : `${error.message}.`;
  return `${message} Connect to a MediaGarrd server that has finished setup on the Dashboard to manage its configuration.`;
}

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
  if (needsPairing(state)) {
    const message = state.pairing === 'REJECTED'
      ? `${state.activeServer} rejected this client's token. Pair again to continue.`
      : `This client is not paired with ${state.activeServer}.`;
    return { ...none, badge: { kind: 'down', label: 'Pairing required' }, message };
  }
  return {
    badge: { kind: state.link === 'DEGRADED' ? 'degraded' : 'ok', label: state.activeServer.replace(/^https?:\/\//, '') },
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

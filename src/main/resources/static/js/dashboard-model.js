function reachableLink(link) {
  return link === 'LIVE' || link === 'DEGRADED';
}

export function canLoadBackups(state) {
  return state.loaded && state.clientReachable && state.activeServer !== null && reachableLink(state.link);
}

export function emptyState() {
  return {
    loaded: false,
    clientReachable: true,
    activeServer: null,
    knownServers: [],
    link: 'DOWN',
    running: null,
    health: null,
    versions: null,
  };
}

function fromSnapshot(payload) {
  return {
    loaded: true,
    clientReachable: true,
    activeServer: payload.activeServer || null,
    knownServers: payload.knownServers || [],
    link: payload.link || 'DOWN',
    running: payload.running ?? null,
    health: payload.health || null,
    versions: payload.versions || null,
  };
}

export function reduce(state, name, payload) {
  if (name === 'client') {
    const reachable = Boolean(payload?.reachable);
    return reachable === state.clientReachable ? state : { ...state, clientReachable: reachable };
  }
  if (name === 'snapshot') return fromSnapshot(payload || {});
  const base = state.clientReachable ? state : { ...state, clientReachable: true };
  if (!state.loaded) return base;
  switch (name) {
    case 'link': {
      const link = payload.state || 'DOWN';
      return { ...base, link, running: reachableLink(link) ? base.running : null };
    }
    case 'state':
      return { ...base, running: Boolean(payload.running) };
    case 'run-started':
      return { ...base, running: true };
    case 'run-finished':
      return { ...base, running: false };
    case 'servers':
      return { ...base, knownServers: payload.knownServers || [], activeServer: payload.activeServer || null };
    case 'config':
      return { ...base, health: { backupInterval: payload.backupInterval, backupRetentionCount: payload.backupRetentionCount } };
    case 'versions':
      return { ...base, versions: payload };
    default:
      return base;
  }
}

export function changes(prev, next, eventName) {
  const none = { serverChanged: false, clearBackups: false, refreshBackups: false };
  if (!next.loaded) return none;
  const first = !prev.loaded;
  const serverChanged = !first && prev.activeServer !== next.activeServer;
  const up = canLoadBackups(next);
  const reachedAgain = !first && (
    (!reachableLink(prev.link) && reachableLink(next.link))
    || (!prev.clientReachable && next.clientReachable)
  );
  const finished = prev.running === true && next.running === false;
  const announced = eventName === 'backups-changed' || eventName === 'run-finished';
  return {
    serverChanged,
    clearBackups: serverChanged,
    refreshBackups: up && (first || serverChanged || reachedAgain || finished || announced),
  };
}

export function applyEvent(state, name, payload) {
  const next = reduce(state, name, payload);
  return { state: next, effects: changes(state, next, name) };
}

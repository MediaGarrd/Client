import { createReachability } from './reachability.js';

const EVENTS_URL = '/api/client/events';
const EVENT_NAMES = ['snapshot', 'link', 'state', 'run-started', 'run-finished', 'servers', 'config', 'backups-changed', 'versions', 'ping'];
const RETRY_MIN_MS = 2000;
const RETRY_MAX_MS = 30000;
const SILENCE_LIMIT_MS = 60000;
const SILENCE_CHECK_MS = 15000;
const RECONNECT_GRACE_MS = 8000;

export function initEvents({ onEvent, onConnection }) {
  let source = null;
  let retryTimer = null;
  let retryDelay = RETRY_MIN_MS;
  let lastMessageAt = 0;
  const reachability = createReachability({ graceMs: RECONNECT_GRACE_MS, onChange: onConnection });

  function parse(event) {
    try {
      return JSON.parse(event.data);
    } catch {
      return null;
    }
  }

  function scheduleReopen() {
    if (retryTimer) return;
    retryTimer = setTimeout(() => {
      retryTimer = null;
      open();
    }, retryDelay);
    retryDelay = Math.min(retryDelay * 2, RETRY_MAX_MS);
  }

  function close() {
    reachability.cancel();
    clearTimeout(retryTimer);
    retryTimer = null;
    if (source) source.close();
    source = null;
  }

  function open() {
    if (source || document.hidden) return;
    const created = new EventSource(EVENTS_URL);
    source = created;
    lastMessageAt = Date.now();
    created.addEventListener('open', () => {
      lastMessageAt = Date.now();
      retryDelay = RETRY_MIN_MS;
      reachability.alive();
    });
    created.addEventListener('error', () => {
      if (source !== created) return;
      const closed = created.readyState === EventSource.CLOSED;
      reachability.failed(closed);
      if (!closed) return;
      close();
      scheduleReopen();
    });
    EVENT_NAMES.forEach((name) => created.addEventListener(name, (event) => {
      lastMessageAt = Date.now();
      reachability.alive();
      const payload = parse(event);
      if (payload && name !== 'ping') onEvent(name, payload);
    }));
  }

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) close();
    else open();
  });

  window.addEventListener('pageshow', (event) => {
    if (event.persisted && (!source || source.readyState === EventSource.CLOSED)) {
      close();
      open();
    }
  });

  setInterval(() => {
    if (source && Date.now() - lastMessageAt > SILENCE_LIMIT_MS) {
      close();
      open();
    }
  }, SILENCE_CHECK_MS);

  open();

  return { open, close };
}

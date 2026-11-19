let loginRequiredHandler = () => {};
let pairingRequiredHandler = () => {};

export function onLoginRequired(handler) {
  loginRequiredHandler = handler;
}

export function onPairingRequired(handler) {
  pairingRequiredHandler = handler;
}

export function loginRequired() {
  loginRequiredHandler();
}

export function retryAfterSeconds(value, now = Date.now()) {
  if (value === null || value === undefined || String(value).trim() === '') return null;
  const text = String(value).trim();
  if (/^\d+$/.test(text)) return Number(text);
  const date = Date.parse(text);
  return Number.isFinite(date) ? Math.max(0, Math.ceil((date - now) / 1000)) : null;
}

export function errorMessage(data, fallback) {
  const body = data && typeof data === 'object' ? data : {};
  for (const candidate of [body.message, body.error]) {
    if (typeof candidate === 'string' && candidate.trim()) return candidate;
  }
  return fallback;
}

export function fallbackMessage(status) {
  if (status === 502 || status === 504) return 'The client or a proxy in front of it did not respond in time.';
  return `The client answered with an error (${status}).`;
}

export function toApiError({ status, data, retryAfter }) {
  const error = new Error(errorMessage(data, fallbackMessage(status)));
  error.status = status;
  error.pairing = data?.pairing;
  error.retryAfter = retryAfterSeconds(retryAfter);
  return error;
}

async function request(method, url, body) {
  const response = await fetch(url, {
    method,
    headers: body === undefined
      ? { 'X-MediaGarrd-Request': '1' }
      : { 'Content-Type': 'application/json', 'X-MediaGarrd-Request': '1' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    if (response.status === 401 && data?.login === 'required') loginRequiredHandler();
    const error = toApiError({
      method,
      url,
      status: response.status,
      data,
      retryAfter: response.headers?.get('Retry-After'),
    });
    if (error.pairing === 'required') pairingRequiredHandler();
    throw error;
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

const encode = encodeURIComponent;

export const api = {
  authStatus: () => request('GET', '/api/auth/status'),
  login: (password) => request('POST', '/api/auth/login', { password }),
  setPassword: (password) => request('POST', '/api/auth/set-password', { password }),
  logout: () => request('POST', '/api/auth/logout'),
  pair: (pin) => request('POST', '/api/client/pairing', { pin }),
  createPairingCode: () => request('POST', '/api/client/pairing/code'),
  unpair: () => request('DELETE', '/api/client/pairing'),
  status: () => request('GET', '/api/client/status'),
  discover: () => request('POST', '/api/client/discover'),
  dashboard: () => request('GET', '/api/client/dashboard'),
  selectServer: (selection) => request('PUT', '/api/client/server', selection),
  listBackups: () => request('GET', '/api/client/backups'),
  runBackup: () => request('POST', '/api/client/backups/run'),
  pickupLatest: () => request('POST', '/api/client/pickup'),
  pickupById: (id) => request('POST', `/api/client/pickup/${encode(id)}`),
  pickupProgress: (taskId) => request('GET', `/api/client/pickup/progress/${encode(taskId)}`),
  updatePickupInterval: (interval) => request('PUT', '/api/client/pickup-interval', { interval }),
  serverConfig: () => request('GET', '/api/client/server-config'),
  updateServerConfig: (config) => request('PUT', '/api/client/server-config', config),
  webhook: () => request('GET', '/api/client/notifications/webhook'),
  updateWebhook: (url) => request('PUT', '/api/client/notifications/webhook', { url }),
  testWebhook: () => request('POST', '/api/client/notifications/webhook/test'),
  listServices: () => request('GET', '/api/client/services'),
  addService: (service) => request('POST', '/api/client/services', service),
  updateService: (name, service) => request('PUT', `/api/client/services/${encode(name)}`, service),
  removeService: (name) => request('DELETE', `/api/client/services/${encode(name)}`),
};

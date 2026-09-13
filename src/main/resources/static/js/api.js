async function request(method, url, body) {
  const response = await fetch(url, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.error || data.message || `${method} ${url} failed (${response.status})`);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

const encode = encodeURIComponent;

export const api = {
  status: () => request('GET', '/api/client/status'),
  discover: () => request('POST', '/api/client/discover'),
  serverHealth: () => request('GET', '/api/client/server-health'),
  selectServer: (selection) => request('PUT', '/api/client/server', selection),
  listBackups: () => request('GET', '/api/client/backups'),
  runBackup: () => request('POST', '/api/client/backups/run'),
  pickupLatest: () => request('POST', '/api/client/pickup'),
  pickupById: (id) => request('POST', `/api/client/pickup/${encode(id)}`),
  pickupProgress: (taskId) => request('GET', `/api/client/pickup/progress/${encode(taskId)}`),
  updatePickupInterval: (interval) => request('PUT', '/api/client/pickup-interval', { interval }),
  serverConfig: () => request('GET', '/api/client/server-config'),
  updateServerConfig: (config) => request('PUT', '/api/client/server-config', config),
  listServices: () => request('GET', '/api/client/services'),
  addService: (service) => request('POST', '/api/client/services', service),
  updateService: (name, service) => request('PUT', `/api/client/services/${encode(name)}`, service),
  removeService: (name) => request('DELETE', `/api/client/services/${encode(name)}`),
};

export function createNotifier(banner) {
  function show(message, kind) {
    banner.textContent = message;
    banner.className = `banner visible ${kind}`;
  }

  return {
    ok: (message) => show(message, 'ok'),
    error: (error) => show(error.message || String(error), 'error'),
    clear: () => { banner.className = 'banner'; },
  };
}

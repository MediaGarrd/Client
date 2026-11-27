export function createNotifier(banner) {
  function show(message, kind) {
    banner.textContent = message;
    banner.className = `banner visible ${kind}`;
    banner.setAttribute('aria-live', kind === 'error' ? 'assertive' : 'polite');
    if (kind !== 'ok' && banner.scrollIntoView) banner.scrollIntoView({ block: 'nearest' });
  }

  return {
    ok: (message) => show(message, 'ok'),
    warn: (message) => show(message, 'warn'),
    error: (error) => show(error.message || String(error), 'error'),
    clear: () => { banner.className = 'banner'; },
  };
}

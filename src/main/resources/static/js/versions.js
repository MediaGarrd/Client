import { versionsView } from './view-model.js';

export function initVersions(root) {
  const rows = {
    server: root.querySelector('[data-row="server"]'),
    client: root.querySelector('[data-row="client"]'),
  };

  function renderRow(row, view) {
    const link = row.querySelector('[data-link]');
    row.classList.remove('current', 'outdated', 'unknown', 'checking');
    row.classList.add(view.kind);
    row.querySelector('[data-version]').textContent = view.version;
    row.querySelector('[data-state]').textContent = view.label;
    link.hidden = !view.href;
    if (view.href) link.href = view.href;
    else link.removeAttribute('href');
  }

  return {
    render(versions) {
      const view = versionsView(versions);
      renderRow(rows.server, view.server);
      renderRow(rows.client, view.client);
    },
  };
}

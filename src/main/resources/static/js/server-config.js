import { api } from './api.js';
import { initServices } from './services.js';
import { initSettings } from './settings.js';

export function initServerConfig(root, notify) {
  const content = root.querySelector('[data-config-content]');
  const unavailable = root.querySelector('[data-unavailable]');
  const settings = initSettings(root.querySelector('#settingsForm'), notify);
  const services = initServices(root.querySelector('#services'), notify);

  async function load() {
    try {
      const [config, list] = await Promise.all([api.serverConfig(), api.listServices()]);
      settings.show(config);
      services.show(list);
      content.hidden = false;
      unavailable.hidden = true;
    } catch (error) {
      content.hidden = true;
      unavailable.hidden = false;
      unavailable.querySelector('[data-reason]').textContent =
        `${error.message}. Connect to a MediaGarrd server that has finished setup on the Dashboard to manage its configuration.`;
    }
  }

  return {
    load,
    serverChanged() {
      services.resetForm();
      if (!root.hidden) load();
    },
  };
}

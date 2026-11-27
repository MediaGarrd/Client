import { api } from './api.js';
import { initServices } from './services.js';
import { initWebhook } from './webhook.js';
import { initSettings } from './settings.js';
import { configUnavailableMessage } from './view-model.js';

export function initServerConfig(root, notify) {
  const content = root.querySelector('[data-config-content]');
  const unavailable = root.querySelector('[data-unavailable]');
  const settings = initSettings(root.querySelector('#settingsForm'), notify);
  const webhook = initWebhook(root.querySelector('#webhookForm'), notify);
  const services = initServices(root.querySelector('#services'), notify);

  let latestLoad = 0;

  async function load() {
    const mine = ++latestLoad;
    try {
      const [config, list] = await Promise.all([api.serverConfig(), api.listServices()]);
      if (mine !== latestLoad) return;
      settings.show(config);
      services.show(list);
      webhook.load();
      content.hidden = false;
      unavailable.hidden = true;
    } catch (error) {
      if (mine !== latestLoad) return;
      content.hidden = true;
      unavailable.hidden = false;
      unavailable.querySelector('[data-reason]').textContent = configUnavailableMessage(error);
    }
  }

  return {
    load,
    serverChanged() {
      services.resetForm();
      webhook.reset();
      if (!root.hidden) load();
    },
  };
}

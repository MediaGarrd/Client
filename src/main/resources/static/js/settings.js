import { api } from './api.js';
import { seconds, toDuration } from './format.js';

export function initSettings(form, notify) {
  const { backupLocation, intervalHours, retentionCount } = form.elements;

  function show(config) {
    backupLocation.value = config.backupLocation;
    intervalHours.value = seconds(config.backupInterval) / 3600;
    retentionCount.value = config.retentionCount;
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    notify.clear();
    try {
      show(await api.updateServerConfig({
        backupLocation: backupLocation.value,
        retentionCount: Number(retentionCount.value),
        backupInterval: toDuration(Number(intervalHours.value) * 60),
      }));
      notify.ok('Backup settings saved');
    } catch (error) {
      notify.error(error);
    }
  });

  return { show };
}

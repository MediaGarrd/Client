import { api } from './api.js';
import { intervalProblem, toDuration, toHours } from './format.js';

export function initSettings(form, notify) {
  const { backupLocation, intervalHours, retentionCount } = form.elements;

  function show(config) {
    backupLocation.value = config.backupLocation;
    intervalHours.value = toHours(config.backupInterval);
    retentionCount.value = config.retentionCount;
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    notify.clear();
    const problem = intervalProblem(Number(intervalHours.value));
    if (problem) {
      notify.error(new Error(problem));
      return;
    }
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

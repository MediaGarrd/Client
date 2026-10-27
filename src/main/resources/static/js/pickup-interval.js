import { api } from './api.js';
import { seconds, toDuration } from './format.js';

export function initPickupInterval(form, notify) {
  const minutes = form.elements.minutes;

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    notify.clear();
    try {
      const status = await api.updatePickupInterval(toDuration(Number(minutes.value)));
      show(status.pickupInterval);
      notify.ok('Pickup interval saved');
    } catch (error) {
      notify.error(error);
    }
  });

  function show(interval) {
    const value = Math.round(seconds(interval) / 60);
    if (value) minutes.value = value;
  }

  return { show };
}

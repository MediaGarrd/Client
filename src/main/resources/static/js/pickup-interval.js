import { api } from './api.js';
import { seconds, toDuration } from './format.js';

export function initPickupInterval(form, notify) {
  const minutes = form.elements.minutes;
  let edited = false;

  minutes.addEventListener('input', () => { edited = true; });

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    notify.clear();
    try {
      const status = await api.updatePickupInterval(toDuration(Number(minutes.value)));
      edited = false;
      show(status.pickupInterval);
      notify.ok('Pickup interval saved');
    } catch (error) {
      notify.error(error);
    }
  });

  function show(interval) {
    if (edited) return;
    const value = Math.round(seconds(interval) / 60);
    if (value) minutes.value = value;
  }

  return { show };
}

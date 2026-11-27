import { api } from './api.js';
import { withBusy } from './busy.js';

export function describeStatus(status) {
  return status && status.configured ? `Configured: ${status.target}` : 'Not configured';
}

export function describeTest(result) {
  if (result && result.delivered) return { ok: true, message: 'Test message delivered' };
  const reason = result && result.error ? result.error : 'no reason given';
  return { ok: false, message: `Test message was not delivered: ${reason}` };
}

export function initWebhook(form, notify) {
  const { url } = form.elements;
  const label = form.querySelector('[data-webhook-status]');
  const testButton = form.querySelector('[data-webhook-test]');
  const clearButton = form.querySelector('[data-webhook-clear]');
  const saveButton = form.querySelector('button[type="submit"]');
  let latest = 0;

  function show(status) {
    label.textContent = describeStatus(status);
    url.value = '';
  }

  async function load() {
    const mine = ++latest;
    label.textContent = 'Checking…';
    try {
      const status = await api.webhook();
      if (mine === latest) label.textContent = describeStatus(status);
    } catch (error) {
      if (mine === latest) label.textContent = `Unavailable: ${error.message}`;
    }
  }

  function reset() {
    latest++;
    url.value = '';
    label.textContent = 'Checking…';
  }

  async function save(value) {
    const mine = latest;
    const status = await api.updateWebhook(value);
    if (mine === latest) show(status);
  }

  async function exclusive(button, task) {
    const others = [saveButton, testButton, clearButton].filter((other) => other !== button);
    if (others.some((other) => other.disabled)) return;
    others.forEach((other) => { other.disabled = true; });
    try {
      await withBusy(button, task);
    } finally {
      others.forEach((other) => { other.disabled = false; });
    }
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    notify.clear();
    const value = url.value.trim();
    if (!value) {
      notify.error(new Error('Enter a webhook URL, or use Clear to turn notifications off'));
      return;
    }
    await exclusive(saveButton, async () => {
      try {
        await save(value);
        notify.ok('Webhook saved');
      } catch (error) {
        notify.error(error);
      }
    });
  });

  clearButton.addEventListener('click', () => exclusive(clearButton, async () => {
    notify.clear();
    try {
      await save('');
      notify.ok('Webhook cleared');
    } catch (error) {
      notify.error(error);
    }
  }));

  testButton.addEventListener('click', () => exclusive(testButton, async () => {
    notify.clear();
    try {
      const typed = url.value.trim();
      if (typed) await save(typed);
      const outcome = describeTest(await api.testWebhook());
      if (outcome.ok) notify.ok(outcome.message);
      else notify.error(new Error(outcome.message));
    } catch (error) {
      notify.error(error);
    }
  }));

  return { load, reset, show };
}

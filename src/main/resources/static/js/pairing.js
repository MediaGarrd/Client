import { api } from './api.js';
import { withBusy } from './busy.js';
import { codeView, credentialProblem, pairingNote, pairingPanel } from './pairing-model.js';

export function initPairing(root, notify, { resync }) {
  const pairForm = root.querySelector('[data-pair-form]');
  const manage = root.querySelector('[data-manage]');
  const note = root.querySelector('[data-pair-note]');
  const codeBox = root.querySelector('[data-code-box]');
  const codeText = root.querySelector('[data-code]');
  const codeExpiry = root.querySelector('[data-code-expiry]');
  const createCode = root.querySelector('[data-create-code]');
  const unpair = root.querySelector('[data-unpair]');
  let code = null;
  let timer = null;

  function renderCode() {
    const view = codeView(code, Date.now());
    codeBox.hidden = !view;
    if (!view) return;
    codeText.textContent = view.text;
    codeExpiry.textContent = view.expires;
  }

  function forgetCode() {
    code = null;
    clearInterval(timer);
    timer = null;
    renderCode();
  }

  function render(state) {
    const panel = pairingPanel(state);
    root.hidden = panel === 'hidden';
    pairForm.hidden = panel !== 'pair';
    manage.hidden = panel !== 'manage';
    if (panel === 'pair') note.textContent = pairingNote(state);
    if (panel !== 'manage') forgetCode();
  }

  pairForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    const field = pairForm.elements.pin;
    const problem = credentialProblem(field.value);
    if (problem) {
      notify.error(problem);
      return;
    }
    notify.clear();
    await withBusy(pairForm.querySelector('button'), async () => {
      try {
        const result = await api.pair(field.value.trim());
        field.value = '';
        if (result?.note) notify.warn(result.note);
        else notify.ok('Paired with the server');
        await resync();
      } catch (error) {
        notify.error(error);
      }
    });
  });

  createCode.addEventListener('click', async () => {
    notify.clear();
    await withBusy(createCode, async () => {
      try {
        code = await api.createPairingCode();
        renderCode();
        clearInterval(timer);
        timer = setInterval(renderCode, 1000);
      } catch (error) {
        notify.error(error);
      }
    });
  });

  unpair.addEventListener('click', async () => {
    if (!confirm('Unpair this client from the server? It will need a new PIN or pairing code to connect again.')) return;
    notify.clear();
    await withBusy(unpair, async () => {
      try {
        const result = await api.unpair();
        forgetCode();
        if (result?.serverRevoked) notify.ok('Unpaired. The server no longer accepts this client.');
        else notify.warn(result?.warning || 'Unpaired locally, but the server could not be told to revoke this client. It may still list this client as paired; remove it with DELETE /api/v1/clients/{id} on the server.');
        await resync();
      } catch (error) {
        notify.error(error);
      }
    });
  });

  return { render };
}

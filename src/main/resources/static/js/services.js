import { api } from './api.js';

export function initServices(root, notify) {
  const form = root.querySelector('form');
  const title = root.querySelector('[data-form-title]');
  const paths = root.querySelector('[data-paths]');
  const cancel = root.querySelector('[data-cancel]');
  const rows = root.querySelector('tbody');
  const empty = root.querySelector('[data-empty]');
  const pathTemplate = root.querySelector('#pathTemplate');
  const rowTemplate = root.querySelector('#serviceTemplate');
  let editing = null;

  function addPathInput(value = '') {
    const row = pathTemplate.content.firstElementChild.cloneNode(true);
    row.querySelector('input').value = value;
    row.querySelector('button').addEventListener('click', () => {
      row.remove();
      if (!paths.children.length) addPathInput();
    });
    paths.append(row);
  }

  function resetForm() {
    editing = null;
    form.reset();
    paths.replaceChildren();
    addPathInput();
    title.textContent = 'Add Service';
    cancel.hidden = true;
  }

  function editService(service) {
    editing = service.name;
    form.elements.name.value = service.name;
    paths.replaceChildren();
    service.paths.forEach((path) => addPathInput(path));
    title.textContent = `Edit ${service.name}`;
    cancel.hidden = false;
    form.elements.name.focus();
  }

  async function removeService(service) {
    if (!confirm(`Remove ${service.name}? Existing backups are kept.`)) return;
    notify.clear();
    try {
      await api.removeService(service.name);
      if (editing === service.name) resetForm();
      notify.ok(`Removed ${service.name}`);
      await refresh();
    } catch (error) {
      notify.error(error);
    }
  }

  function renderRow(service) {
    const row = rowTemplate.content.firstElementChild.cloneNode(true);
    row.querySelector('[data-name]').textContent = service.name;
    row.querySelector('[data-paths]').textContent = service.paths.join('\n');
    row.querySelector('[data-edit]').addEventListener('click', () => editService(service));
    row.querySelector('[data-remove]').addEventListener('click', () => removeService(service));
    return row;
  }

  function show(services) {
    rows.replaceChildren(...services.map(renderRow));
    empty.hidden = services.length > 0;
  }

  async function refresh() {
    show(await api.listServices());
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    notify.clear();
    const service = {
      name: form.elements.name.value,
      paths: [...paths.querySelectorAll('input')].map((input) => input.value),
    };
    try {
      const saved = editing ? await api.updateService(editing, service) : await api.addService(service);
      notify.ok(`Saved ${saved.name}`);
      resetForm();
      await refresh();
    } catch (error) {
      notify.error(error);
    }
  });

  root.querySelector('[data-add-path]').addEventListener('click', () => addPathInput());
  cancel.addEventListener('click', resetForm);

  resetForm();
  return { show, resetForm };
}

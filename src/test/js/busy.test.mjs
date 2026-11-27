import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { withBusy } from '../../main/resources/static/js/busy.js';

const savedDocument = globalThis.document;
afterEach(() => {
  if (savedDocument === undefined) delete globalThis.document;
  else globalThis.document = savedDocument;
});

function button(focused) {
  const element = { disabled: false, focused: 0, focus() { this.focused++; } };
  globalThis.document = { activeElement: focused ? element : null };
  return element;
}

test('the button is disabled while the task runs and restored afterwards', async () => {
  const element = button(false);
  let during;
  const result = await withBusy(element, async () => { during = element.disabled; return 7; });
  assert.equal(during, true);
  assert.equal(element.disabled, false);
  assert.equal(result, 7);
});

test('focus returns to a button that had it, and only then', async () => {
  const focused = button(true);
  await withBusy(focused, async () => {});
  assert.equal(focused.focused, 1);
  const other = button(false);
  await withBusy(other, async () => {});
  assert.equal(other.focused, 0);
});

test('a second activation while busy is ignored', async () => {
  const element = button(false);
  let runs = 0;
  let release;
  const first = withBusy(element, () => new Promise((resolve) => { runs++; release = resolve; }));
  assert.equal(await withBusy(element, async () => { runs++; }), undefined);
  release();
  await first;
  assert.equal(runs, 1);
});

test('the button is restored when the task throws', async () => {
  const element = button(true);
  await assert.rejects(withBusy(element, async () => { throw new Error('boom'); }), /boom/);
  assert.equal(element.disabled, false);
});

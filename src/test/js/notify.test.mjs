import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createNotifier } from '../../main/resources/static/js/notify.js';

function banner() {
  const attributes = {};
  return {
    textContent: '',
    className: 'banner',
    attributes,
    scrolled: 0,
    setAttribute(name, value) { attributes[name] = value; },
    scrollIntoView() { this.scrolled++; },
  };
}

test('errors are assertive and scrolled into view', () => {
  const element = banner();
  createNotifier(element).error(new Error('Nope'));
  assert.equal(element.textContent, 'Nope');
  assert.equal(element.className, 'banner visible error');
  assert.equal(element.attributes['aria-live'], 'assertive');
  assert.equal(element.scrolled, 1);
});

test('success is polite and does not scroll; warnings scroll', () => {
  const element = banner();
  const notify = createNotifier(element);
  notify.ok('Saved');
  assert.equal(element.className, 'banner visible ok');
  assert.equal(element.attributes['aria-live'], 'polite');
  assert.equal(element.scrolled, 0);
  notify.warn('Careful');
  assert.equal(element.className, 'banner visible warn');
  assert.equal(element.scrolled, 1);
  notify.clear();
  assert.equal(element.className, 'banner');
});

test('a non-Error rejection is shown as text', () => {
  const element = banner();
  createNotifier(element).error('plain');
  assert.equal(element.textContent, 'plain');
});

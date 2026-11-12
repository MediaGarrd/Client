import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createReachability } from '../../main/resources/static/js/reachability.js';

function harness() {
  const changes = [];
  const timers = new Map();
  let next = 1;
  const reachability = createReachability({
    graceMs: 8000,
    onChange: (reachable) => changes.push(reachable),
    setTimer: (callback, ms) => {
      const id = next++;
      timers.set(id, { callback, ms });
      return id;
    },
    clearTimer: (id) => timers.delete(id),
  });
  const fire = () => [...timers.values()].forEach((timer) => { timers.clear(); timer.callback(); });
  return { reachability, changes, timers, fire };
}

test('a transient error followed by open never declares the client unreachable', () => {
  const { reachability, changes, timers, fire } = harness();
  reachability.failed(false);
  assert.equal(timers.size, 1);
  assert.deepEqual([...timers.values()].map((timer) => timer.ms), [8000]);
  reachability.alive();
  fire();
  assert.deepEqual(changes, [true]);
  assert.equal(timers.size, 0);
});

test('an error that is not followed by open declares the client unreachable after the grace period', () => {
  const { reachability, changes, fire } = harness();
  reachability.failed(false);
  assert.deepEqual(changes, []);
  fire();
  assert.deepEqual(changes, [false]);
  reachability.alive();
  assert.deepEqual(changes, [false, true]);
});

test('repeated errors during the grace period do not extend it', () => {
  const { reachability, timers } = harness();
  reachability.failed(false);
  reachability.failed(false);
  reachability.failed(false);
  assert.equal(timers.size, 1);
});

test('a closed stream is declared unreachable at once and cancels a pending grace timer', () => {
  const { reachability, changes, timers } = harness();
  reachability.failed(false);
  reachability.failed(true);
  assert.deepEqual(changes, [false]);
  assert.equal(timers.size, 0);
});

test('any sign of life after a pending error cancels it', () => {
  const { reachability, changes, timers, fire } = harness();
  reachability.failed(false);
  reachability.alive();
  reachability.failed(false);
  assert.equal(timers.size, 1);
  fire();
  assert.deepEqual(changes, [true, false]);
});

test('cancelling drops a pending grace timer without reporting anything', () => {
  const { reachability, changes, timers, fire } = harness();
  reachability.failed(false);
  reachability.cancel();
  fire();
  assert.equal(timers.size, 0);
  assert.deepEqual(changes, []);
});

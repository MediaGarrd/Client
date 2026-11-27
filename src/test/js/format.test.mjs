import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { bytes, formatInterval, intervalProblem, percent, pickupProblem, relativeTime, seconds, toDuration, toHours } from '../../main/resources/static/js/format.js';

const realNow = Date.now;
afterEach(() => { Date.now = realNow; });

test('seconds reads ISO durations, plain numbers and garbage', () => {
  assert.equal(seconds('PT6H'), 21600);
  assert.equal(seconds('PT1H30M'), 5400);
  assert.equal(seconds('PT45S'), 45);
  assert.equal(seconds('pt0.5H'), 1800);
  assert.equal(seconds(90), 90);
  assert.equal(seconds('nonsense'), 0);
  assert.equal(seconds(null), 0);
  assert.equal(seconds(undefined), 0);
});

test('toDuration uses whole minutes when it can and seconds otherwise', () => {
  assert.equal(toDuration(5), 'PT5M');
  assert.equal(toDuration(90), 'PT90M');
  assert.equal(toDuration(0.5), 'PT30S');
  assert.equal(toDuration(0.25 * 60), 'PT15M');
});

test('toHours rounds to four decimals and survives a round trip', () => {
  assert.equal(toHours('PT6H'), 6);
  assert.equal(toHours('PT15M'), 0.25);
  assert.equal(toHours('PT20M'), 0.3333);
  assert.equal(seconds(toDuration(toHours('PT20M') * 60)), 1200);
  assert.equal(seconds(toDuration(toHours('PT5M') * 60)), 300);
});

test('formatInterval is compact and shows a dash for zero or unknown', () => {
  assert.equal(formatInterval('PT6H'), '6h');
  assert.equal(formatInterval('PT90M'), '1h 30m');
  assert.equal(formatInterval('PT45S'), '45s');
  assert.equal(formatInterval('PT0S'), '—');
  assert.equal(formatInterval(null), '—');
});

test('bytes picks a unit and caps at TB', () => {
  assert.equal(bytes(0), '0 B');
  assert.equal(bytes(undefined), '0 B');
  assert.equal(bytes(512), '512 B');
  assert.equal(bytes(1536), '1.5 KB');
  assert.equal(bytes(5 * 1024 ** 2), '5.0 MB');
  assert.equal(bytes(3 * 1024 ** 4), '3.0 TB');
  assert.equal(bytes(2048 * 1024 ** 4), '2048.0 TB');
});

test('relativeTime buckets and clamps the future', () => {
  Date.now = () => Date.parse('2026-01-10T12:00:00Z');
  assert.equal(relativeTime('2026-01-10T11:59:30Z'), '30s ago');
  assert.equal(relativeTime('2026-01-10T11:55:00Z'), '5m ago');
  assert.equal(relativeTime('2026-01-10T09:00:00Z'), '3h ago');
  assert.equal(relativeTime('2026-01-08T12:00:00Z'), '2d ago');
  assert.equal(relativeTime('2026-01-11T12:00:00Z'), '0s ago');
  assert.equal(relativeTime('not a date'), '—');
  assert.equal(relativeTime(undefined), '—');
});

test('percent is clamped and safe for an unknown total', () => {
  assert.equal(percent(50, 200), 25);
  assert.equal(percent(300, 200), 100);
  assert.equal(percent(-5, 200), 0);
  assert.equal(percent(5, 0), 0);
  assert.equal(percent(5, undefined), 0);
});

test('intervalProblem accepts 15 minutes to 365 days and names the bounds otherwise', () => {
  const message = 'Backup interval must be between 15 minutes and 365 days';
  assert.equal(intervalProblem(0.25), null);
  assert.equal(intervalProblem(12), null);
  assert.equal(intervalProblem(8760), null);
  for (const hours of [0, -1, 0.24, 8760.01, 1e9, NaN, Infinity]) assert.equal(intervalProblem(hours), message);
});

test('pickupProblem accepts 1 minute to 365 days and rejects the rest', () => {
  assert.equal(pickupProblem(1), null);
  assert.equal(pickupProblem(525600), null);
  assert.ok(pickupProblem(525601));
  assert.ok(pickupProblem(0.5));
  assert.ok(pickupProblem(0));
  assert.ok(pickupProblem(NaN));
  assert.ok(pickupProblem(Infinity));
});

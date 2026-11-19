import assert from 'node:assert/strict';
import { test } from 'node:test';
import { authScreen, passwordProblem } from '../../main/resources/static/js/auth-model.js';

test('the auth screen follows the auth status', () => {
  const cases = [
    [null, 'loading'],
    [undefined, 'loading'],
    [{ passwordSet: true, authenticated: true }, 'none'],
    [{ passwordSet: true, authenticated: false }, 'login'],
    [{ passwordSet: false, authenticated: false }, 'set-password'],
  ];
  for (const [status, screen] of cases) {
    assert.equal(authScreen(status), screen);
  }
});

test('a new password needs eight characters and a matching repeat', () => {
  assert.match(passwordProblem('short', 'short'), /at least 8/);
  assert.match(passwordProblem('long enough', 'different'), /do not match/);
  assert.equal(passwordProblem('long enough', 'long enough'), null);
});

test('the lockout message counts seconds and disappears at zero', async () => {
  const { lockoutMessage } = await import('../../main/resources/static/js/auth-model.js');
  assert.equal(lockoutMessage(1), 'Too many login attempts. Try again in 1 second.');
  assert.equal(lockoutMessage(30), 'Too many login attempts. Try again in 30 seconds.');
  assert.equal(lockoutMessage(0), null);
});

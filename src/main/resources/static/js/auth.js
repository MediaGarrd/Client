import { api, onLoginRequired } from './api.js';
import { authScreen, lockoutMessage, passwordProblem } from './auth-model.js';

const RETRY_MIN_MS = 1000;
const RETRY_MAX_MS = 10000;
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function initAuth({ root, logoutButton, background = [] }) {
  const loginForm = root.querySelector('[data-login-form]');
  const setForm = root.querySelector('[data-set-form]');
  const title = root.querySelector('[data-auth-title]');
  const error = root.querySelector('[data-auth-error]');
  let reloading = false;
  let lockout = null;

  function reload() {
    if (reloading) return;
    reloading = true;
    location.reload();
  }

  function clearLockout() {
    clearInterval(lockout);
    lockout = null;
  }

  function show(screen) {
    clearLockout();
    root.hidden = screen === 'none';
    background.forEach((element) => { element.inert = screen !== 'none'; });
    loginForm.hidden = screen !== 'login';
    setForm.hidden = screen !== 'set-password';
    title.textContent = screen === 'set-password' ? 'Set a password' : 'Sign in';
    error.textContent = '';
    loginForm.querySelector('button').disabled = false;
    setForm.querySelector('button').disabled = false;
    if (screen !== 'none') (screen === 'login' ? loginForm : setForm).elements.password.focus();
  }

  function holdUntil(form, seconds) {
    const button = form.querySelector('button');
    const until = Date.now() + seconds * 1000;
    button.disabled = true;
    clearLockout();
    const tick = () => {
      const remaining = Math.ceil((until - Date.now()) / 1000);
      if (remaining <= 0) {
        clearLockout();
        error.textContent = '';
        button.disabled = false;
        form.elements.password.focus();
        return;
      }
      error.textContent = lockoutMessage(remaining);
    };
    tick();
    lockout = setInterval(tick, 1000);
  }

  async function submit(form, action) {
    const button = form.querySelector('button');
    const refocus = document.activeElement === button;
    error.textContent = '';
    button.disabled = true;
    try {
      await action();
      reload();
    } catch (failure) {
      error.textContent = failure.message;
      if (failure.status === 429 && failure.retryAfter > 0) {
        holdUntil(form, failure.retryAfter);
        return;
      }
      if (failure.status === 409) {
        await current().catch(() => {});
        error.textContent = failure.message;
        return;
      }
      button.disabled = false;
      if (refocus) form.elements.password.focus();
    }
  }

  async function current() {
    const screen = authScreen(await api.authStatus());
    if (screen === 'none') {
      reload();
      return screen;
    }
    show(screen);
    logoutButton.hidden = true;
    return screen;
  }

  loginForm.addEventListener('submit', (event) => {
    event.preventDefault();
    submit(loginForm, () => api.login(loginForm.elements.password.value));
  });

  setForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const { password, confirm } = setForm.elements;
    const problem = passwordProblem(password.value, confirm.value);
    if (problem) {
      error.textContent = problem;
      return;
    }
    submit(setForm, () => api.setPassword(password.value));
  });

  logoutButton.addEventListener('click', async () => {
    try {
      await api.logout();
    } finally {
      reload();
    }
  });

  onLoginRequired(reload);

  function showConnecting(message) {
    root.hidden = false;
    background.forEach((element) => { element.inert = true; });
    loginForm.hidden = true;
    setForm.hidden = true;
    title.textContent = 'MediaGarrd Client';
    error.textContent = message;
  }

  return {
    async ensureSignedIn() {
      let delay = RETRY_MIN_MS;
      for (;;) {
        let screen = 'loading';
        try {
          screen = authScreen(await api.authStatus());
        } catch (failure) {
          showConnecting(`Client unreachable: ${failure.message}. Retrying…`);
        }
        if (screen !== 'loading') {
          show(screen);
          logoutButton.hidden = screen !== 'none';
          return screen === 'none';
        }
        if (!error.textContent) showConnecting('Connecting…');
        await wait(delay);
        delay = Math.min(delay * 2, RETRY_MAX_MS);
      }
    },
  };
}

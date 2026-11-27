export const MIN_PASSWORD_LENGTH = 8;

export function authScreen(status) {
  if (!status) return 'loading';
  if (status.authenticated) return 'none';
  return status.passwordSet ? 'login' : 'set-password';
}

export function passwordProblem(password, confirm) {
  if (password.length < MIN_PASSWORD_LENGTH) return `Use at least ${MIN_PASSWORD_LENGTH} characters.`;
  if (password !== confirm) return 'The passwords do not match.';
  return null;
}

export function lockoutMessage(seconds) {
  return seconds > 0 ? `Too many login attempts. Try again in ${seconds} second${seconds === 1 ? '' : 's'}.` : null;
}

export async function withBusy(button, task) {
  if (button.disabled) return undefined;
  const refocus = document.activeElement === button;
  button.disabled = true;
  try {
    return await task();
  } finally {
    button.disabled = false;
    if (refocus) button.focus();
  }
}

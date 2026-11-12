export function createReachability({ graceMs, onChange, setTimer = setTimeout, clearTimer = clearTimeout }) {
  let timer = null;

  function cancel() {
    if (timer === null) return;
    clearTimer(timer);
    timer = null;
  }

  return {
    cancel,
    failed(closed) {
      if (closed) {
        cancel();
        onChange(false);
        return;
      }
      if (timer !== null) return;
      timer = setTimer(() => {
        timer = null;
        onChange(false);
      }, graceMs);
    },
    alive() {
      cancel();
      onChange(true);
    },
  };
}

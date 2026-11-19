const ISO_DURATION = /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/i;

export function seconds(duration) {
  if (typeof duration === 'number') return duration;
  const [, hours = 0, minutes = 0, secs = 0] = ISO_DURATION.exec(duration ?? '') || [];
  return Number(hours) * 3600 + Number(minutes) * 60 + Number(secs);
}

export function toDuration(minutes) {
  const total = Math.round(minutes * 60);
  return total % 60 === 0 ? `PT${total / 60}M` : `PT${total}S`;
}

export const MIN_INTERVAL_SECONDS = 900;
export const MAX_INTERVAL_SECONDS = 31536000;
export const INTERVAL_MESSAGE = 'Backup interval must be between 15 minutes and 365 days';

export function intervalProblem(hours) {
  const total = Math.round(hours * 3600);
  const valid = Number.isFinite(total) && total >= MIN_INTERVAL_SECONDS && total <= MAX_INTERVAL_SECONDS;
  return valid ? null : INTERVAL_MESSAGE;
}

export const MAX_PICKUP_MINUTES = 525600;
export const PICKUP_MESSAGE = 'Pickup interval must be between 1 minute and 365 days (525600 minutes)';

export function pickupProblem(minutes) {
  const valid = Number.isFinite(minutes) && minutes >= 1 && minutes <= MAX_PICKUP_MINUTES;
  return valid ? null : PICKUP_MESSAGE;
}

export const toHours = (duration) => Number((seconds(duration) / 3600).toFixed(4));

export function formatInterval(duration) {
  const total = seconds(duration);
  if (!total) return '—';
  if (total < 60) return `${total}s`;
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  return [hours && `${hours}h`, minutes && `${minutes}m`].filter(Boolean).join(' ');
}

export function bytes(size) {
  if (!size) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let unit = 0;
  let value = size;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(unit > 0 ? 1 : 0)} ${units[unit]}`;
}

export function relativeTime(iso) {
  const time = new Date(iso).getTime();
  if (!Number.isFinite(time)) return '—';
  const elapsed = Math.max(0, Math.floor((Date.now() - time) / 1000));
  if (elapsed < 60) return `${elapsed}s ago`;
  const minutes = Math.floor(elapsed / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

export function percent(written, total) {
  return total > 0 ? Math.max(0, Math.min(100, Math.floor((written / total) * 100))) : 0;
}

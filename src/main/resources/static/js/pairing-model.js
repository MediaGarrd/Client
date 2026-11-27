import { needsPairing } from './dashboard-model.js';

function reachable(link) {
  return link === 'LIVE' || link === 'DEGRADED';
}

export function pairingPanel(state) {
  if (!state.loaded || !state.clientReachable || state.activeServer === null) return 'hidden';
  if (!reachable(state.link)) return 'hidden';
  return needsPairing(state) ? 'pair' : 'manage';
}

export function pairingNote(state) {
  if (state.pairing === 'REJECTED') {
    return 'The server no longer accepts this client. Enter a pairing code from a client that is still paired. The Pairing PIN (logged by the server) works only when no client is paired, or after MEDIAGARRD_OPEN_PAIRING.';
  }
  return 'Enter the Pairing PIN (shown after Finish Setup and in the server logs), or a pairing code created by a client that is already paired.';
}

export function credentialProblem(value) {
  return String(value ?? '').trim() ? null : 'Enter the PIN or pairing code.';
}

export function codeView(code, now) {
  if (!code) return null;
  const left = Math.max(0, Math.round((Date.parse(code.expiresAt) - now) / 1000));
  if (!Number.isFinite(left) || left <= 0) return { text: code.code, expires: 'Expired' };
  const minutes = Math.floor(left / 60);
  const seconds = left % 60;
  return { text: code.code, expires: `Expires in ${minutes}:${String(seconds).padStart(2, '0')}` };
}

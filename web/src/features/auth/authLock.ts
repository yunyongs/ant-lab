const LOCK_NAME = 'antlab-auth';

// Serialises refresh, login and logout across tabs: the browser sends the
// refresh cookie that is current when a request starts, so a second tab must
// start only after the first tab's response has set the rotated cookie.
export function withAuthLock<T>(fn: () => Promise<T>): Promise<T> {
  if (typeof navigator === 'undefined' || !('locks' in navigator)) return fn();
  return navigator.locks.request(LOCK_NAME, { mode: 'exclusive' }, fn);
}

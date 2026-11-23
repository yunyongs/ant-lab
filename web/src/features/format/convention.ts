import { useSyncExternalStore } from 'react';

export type UpDownConvention = 'korean' | 'western';

const KEY = 'antlab.updown';
const listeners = new Set<() => void>();
let current: UpDownConvention | null = null;

function apply(value: UpDownConvention) {
  document.documentElement.dataset.updown = value;
}

// This view preference is the only thing the app keeps in browser storage
// (never a token); a blocked or failing store falls back to the default.
function read(): UpDownConvention {
  try {
    // eslint-disable-next-line no-restricted-globals -- per-viewer colour preference, not a credential
    return localStorage.getItem(KEY) === 'western' ? 'western' : 'korean';
  } catch {
    return 'korean';
  }
}

export function getConvention(): UpDownConvention {
  current ??= read();
  return current;
}

export function initConvention() {
  apply(getConvention());
}

export function setConvention(value: UpDownConvention) {
  current = value;
  apply(value);
  try {
    // eslint-disable-next-line no-restricted-globals -- per-viewer colour preference, not a credential
    localStorage.setItem(KEY, value);
  } catch {
    // The choice still holds for this page view.
  }
  listeners.forEach((listener) => {
    listener();
  });
}

export function resetConventionForTests() {
  current = null;
  delete document.documentElement.dataset.updown;
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useConvention(): UpDownConvention {
  return useSyncExternalStore(subscribe, getConvention);
}

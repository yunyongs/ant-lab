import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server.ts';

// Node-environment tests (proxy, lint, pins) use real sockets; only the
// jsdom tests run behind the request mock. The auth modules read `window`,
// so they are imported only where it exists.
if (typeof document !== 'undefined') {
  installDialogPolyfill();
  const { resetRefreshForTests } = await import('../features/auth/refresh.ts');
  const { resetSessionForTests } = await import('../features/auth/session.ts');
  beforeAll(() => {
    server.listen({ onUnhandledFrame: 'error' });
  });
  afterEach(() => {
    cleanup();
    server.resetHandlers();
    resetSessionForTests();
    resetRefreshForTests();
  });
  afterAll(() => {
    server.close();
  });
}

// jsdom has no HTMLDialogElement.showModal: this stand-in sets `open`, fires
// `close`, and lets Escape close the top modal. Real focus trapping is only
// checked in a browser.
function installDialogPolyfill() {
  const proto = HTMLDialogElement.prototype;
  if (typeof proto.showModal === 'function') return;
  const modals: HTMLDialogElement[] = [];
  proto.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '');
    modals.push(this);
  };
  proto.close = function close(this: HTMLDialogElement) {
    if (!this.hasAttribute('open')) return;
    this.removeAttribute('open');
    const at = modals.indexOf(this);
    if (at >= 0) modals.splice(at, 1);
    this.dispatchEvent(new Event('close'));
  };
  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    const top = modals[modals.length - 1];
    if (top === undefined) return;
    const cancel = new Event('cancel', { cancelable: true });
    top.dispatchEvent(cancel);
    if (!cancel.defaultPrevented) top.close();
  });
}

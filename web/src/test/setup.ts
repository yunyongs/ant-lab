import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server.ts';

// Node-environment tests (proxy, lint, pins) use real sockets; only the
// jsdom tests run behind the request mock. The auth modules read `window`,
// so they are imported only where it exists.
if (typeof document !== 'undefined') {
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

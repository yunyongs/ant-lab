import { expect, type Page } from '@playwright/test';

export const REFRESH_PATH = '/api/v1/auth/refresh';
const SEASONS_PATH = '/api/v1/seasons';

interface AllowedResponse {
  method: string;
  path: string;
  status: number;
}

// The single response the happy path may fail with: the cold start and the
// visit after logout both try to restore a session that does not exist. It is
// allowed only while no member is logged in.
export const ALLOWED_WHILE_LOGGED_OUT: readonly AllowedResponse[] = [
  { method: 'POST', path: REFRESH_PATH, status: 401 },
];

export interface Guards {
  setLoggedIn: (loggedIn: boolean) => void;
  assertClean: () => void;
  bearerRequests: () => number;
}

const pathOf = (url: string): string => new URL(url).pathname;

// Collects everything the browser reports; only paths and status codes are
// kept, never bodies, headers or tokens.
export function attachGuards(page: Page): Guards {
  const violations: string[] = [];
  const observedAllowed = new Set<string>();
  let loggedIn = false;
  let bearerRequests = 0;

  const allowed = (method: string, path: string, status: number): boolean =>
    !loggedIn &&
    ALLOWED_WHILE_LOGGED_OUT.some(
      (entry) =>
        entry.method === method &&
        entry.path === path &&
        entry.status === status,
    );

  page.on('response', (response) => {
    const status = response.status();
    if (status < 400) return;
    const method = response.request().method();
    const path = pathOf(response.url());
    if (allowed(method, path, status)) {
      observedAllowed.add(`${String(status)} ${path}`);
      return;
    }
    violations.push(`response: ${method} ${path} -> ${String(status)}`);
  });

  page.on('requestfailed', (request) => {
    const errorText = request.failure()?.errorText ?? '';
    // A read the app itself cancelled (query cancellation on unmount, StrictMode in
    // development) is not a failure; an aborted write or any other error is.
    if (request.method() === 'GET' && errorText === 'net::ERR_ABORTED') return;
    violations.push(
      `requestfailed: ${request.method()} ${pathOf(request.url())} ${errorText}`,
    );
  });

  page.on('pageerror', (error) => {
    violations.push(`pageerror: ${error.message}`);
  });

  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    const text = message.text();
    // The browser's own line for a failed fetch; the response handler above
    // already judged it, so it is accepted only when that response was allowed.
    const load = /^Failed to load resource: .*status of (\d+)/.exec(text);
    if (load?.[1] !== undefined && message.location().url !== '') {
      const path = pathOf(message.location().url);
      if (observedAllowed.has(`${load[1]} ${path}`)) return;
    }
    violations.push(`console.error: ${text}`);
  });

  page.on('request', (request) => {
    const path = pathOf(request.url());
    if (!path.startsWith(SEASONS_PATH)) return;
    bearerRequests += 1;
    // Presence only; the value is never read into a message.
    if (!(request.headers().authorization ?? '').startsWith('Bearer ')) {
      violations.push(`bearer missing: ${request.method()} ${path}`);
    }
  });

  return {
    setLoggedIn: (value) => {
      loggedIn = value;
    },
    assertClean: () => {
      expect(violations, 'console, page, network and bearer checks').toEqual(
        [],
      );
    },
    bearerRequests: () => bearerRequests,
  };
}

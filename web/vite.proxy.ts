import type { ProxyOptions } from 'vite';

const DEFAULT_API_PORT = 8080;

export function apiPort(env: Record<string, string | undefined>): number {
  const raw = env.ANTLAB_API_PORT;
  if (raw === undefined) return DEFAULT_API_PORT;
  const port = /^\d+$/.test(raw) ? Number.parseInt(raw, 10) : NaN;
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(
      `ANTLAB_API_PORT must be an integer from 1 to 65535, got "${raw}"`,
    );
  }
  return port;
}

// 127.0.0.1, not localhost: the API binds IPv4 loopback and localhost may
// resolve to ::1 first. Host is kept (changeOrigin false) so Location headers
// built from the request Host stay same-origin.
export function apiProxy(
  env: Record<string, string | undefined>,
): Record<string, ProxyOptions> {
  return {
    '/api/': {
      target: `http://127.0.0.1:${apiPort(env)}`,
      changeOrigin: false,
    },
  };
}

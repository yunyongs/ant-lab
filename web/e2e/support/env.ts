import { isAbsolute, resolve } from 'node:path';

export interface E2eEnv {
  apiPort: number;
  webPort: number;
  outDir: string;
}

const DEV_SERVER_PORT = 5173;

function port(name: string, raw: string, min: number): number {
  const value = /^\d+$/.test(raw) ? Number.parseInt(raw, 10) : NaN;
  if (!Number.isInteger(value) || value < min || value > 65535) {
    throw new Error(
      `${name} must be an integer from ${String(min)} to 65535, got "${raw}"`,
    );
  }
  return value;
}

// ANTLAB_API_PORT has no default on purpose: the run must name the API it plays against.
export function readE2eEnv(
  env: Record<string, string | undefined>,
  cwd: string,
): E2eEnv {
  const rawApi = env.ANTLAB_API_PORT;
  if (rawApi === undefined || rawApi === '') {
    throw new Error(
      'ANTLAB_API_PORT is required for the end-to-end run: use scripts/web_e2e.sh, or name the API you started',
    );
  }
  const apiPort = port('ANTLAB_API_PORT', rawApi, 1);
  const webPort = port(
    'ANTLAB_E2E_WEB_PORT',
    env.ANTLAB_E2E_WEB_PORT ?? '5196',
    1024,
  );
  if (webPort === DEV_SERVER_PORT) {
    throw new Error(
      `ANTLAB_E2E_WEB_PORT must not be ${String(DEV_SERVER_PORT)} (the development server's port)`,
    );
  }
  const out = env.ANTLAB_E2E_OUT_DIR ?? 'test-results';
  return {
    apiPort,
    webPort,
    outDir: isAbsolute(out) ? out : resolve(cwd, out),
  };
}

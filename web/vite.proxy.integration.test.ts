// @vitest-environment node
import { createServer as createHttpServer } from 'node:http';
import type { IncomingMessage, Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createServer } from 'vite';
import type { ViteDevServer } from 'vite';

interface Seen {
  method: string;
  url: string;
  host: string;
  body: string;
}

const COOKIE = 'antlab_refresh=x; HttpOnly; SameSite=Strict; Path=/api/v1/auth';

/** Runs `fn` with the variable set, then restores the previous state even if `fn` throws. */
async function withEnv<T>(
  name: string,
  value: string,
  fn: () => Promise<T>,
): Promise<T> {
  const previous = process.env[name];
  process.env[name] = value;
  try {
    return await fn();
  } finally {
    if (previous === undefined) delete process.env[name];
    else process.env[name] = previous;
  }
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    const chunks: Buffer[] = [];
    req.on('data', (c: Buffer) => chunks.push(c));
    req.on('end', () => {
      resolve(Buffer.concat(chunks).toString('utf8'));
    });
  });
}

const originalApiPort = process.env.ANTLAB_API_PORT;
let stub: Server | undefined;
let vite: ViteDevServer | undefined;
let base: string;
const seen: Seen[] = [];

beforeAll(async () => {
  const server = createHttpServer((req, res) => {
    void readBody(req).then((body) => {
      seen.push({
        method: req.method ?? '',
        url: req.url ?? '',
        host: req.headers.host ?? '',
        body,
      });
      res.setHeader('Content-Type', 'application/json');
      res.setHeader('Set-Cookie', COOKIE);
      res.end(JSON.stringify({ ok: true }));
    });
  });
  stub = server;
  // Port 0 lets the OS pick a free port atomically, so nothing is closed and reused.
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const { port: stubPort } = server.address() as AddressInfo;

  await withEnv('ANTLAB_API_PORT', String(stubPort), async () => {
    vite = await createServer({
      configFile: new URL('./vite.config.ts', import.meta.url).pathname,
      root: new URL('.', import.meta.url).pathname,
      server: { port: 0, strictPort: true },
      logLevel: 'silent',
    });
    await vite.listen();
    base = vite.resolvedUrls?.local[0] ?? '';
  });
});

afterAll(async () => {
  await vite?.close();
  if (stub?.listening) await new Promise((r) => stub?.close(r));
});

describe('withEnv', () => {
  it('restores an unset variable after success and after a throw', async () => {
    delete process.env.ANTLAB_TEST_ENV;
    await withEnv('ANTLAB_TEST_ENV', '1', () => {
      expect(process.env.ANTLAB_TEST_ENV).toBe('1');
      return Promise.resolve();
    });
    expect(process.env.ANTLAB_TEST_ENV).toBeUndefined();
    await expect(
      withEnv('ANTLAB_TEST_ENV', '1', () => Promise.reject(new Error('boom'))),
    ).rejects.toThrow('boom');
    expect(process.env.ANTLAB_TEST_ENV).toBeUndefined();
  });

  it('restores a previous value after a throw', async () => {
    process.env.ANTLAB_TEST_ENV = 'before';
    try {
      await expect(
        withEnv('ANTLAB_TEST_ENV', '2', () => Promise.reject(new Error('x'))),
      ).rejects.toThrow('x');
      expect(process.env.ANTLAB_TEST_ENV).toBe('before');
    } finally {
      delete process.env.ANTLAB_TEST_ENV;
    }
  });

  it('leaves ANTLAB_API_PORT untouched once the server is up', () => {
    expect(process.env.ANTLAB_API_PORT).toBe(originalApiPort);
  });
});

describe('dev proxy against a stub backend', () => {
  it('forwards /api unchanged and keeps the browser Host', async () => {
    seen.length = 0;
    const res = await fetch(new URL('/api/v1/pool?x=1', base));
    expect(res.status).toBe(200);
    expect(seen).toHaveLength(1);
    expect(seen[0]?.url).toBe('/api/v1/pool?x=1');
    expect(seen[0]?.host).toBe(new URL(base).host);
  });

  it('passes Set-Cookie through byte for byte', async () => {
    const res = await fetch(new URL('/api/v1/pool', base));
    expect(res.headers.getSetCookie()).toEqual([COOKIE]);
  });

  it('delivers a POST JSON body intact', async () => {
    seen.length = 0;
    const body = JSON.stringify({ name: '시즌', n: 1 });
    await fetch(new URL('/api/v1/seasons', base), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
    });
    expect(seen[0]?.method).toBe('POST');
    expect(seen[0]?.body).toBe(body);
  });

  it('does not forward look-alike paths such as /apix', async () => {
    seen.length = 0;
    await fetch(new URL('/apix', base));
    await fetch(new URL('/api-anything', base));
    expect(seen).toHaveLength(0);
  });

  it('does not forward /readyz', async () => {
    seen.length = 0;
    await fetch(new URL('/readyz', base));
    expect(seen).toHaveLength(0);
  });

  it('answers a non-Problem failure once the backend is gone', async () => {
    await new Promise((r) => stub?.close(r));
    const res = await fetch(new URL('/api/v1/pool', base));
    expect(res.status).toBeGreaterThanOrEqual(500);
    expect(res.headers.get('content-type') ?? '').not.toContain(
      'application/problem+json',
    );
  });
});

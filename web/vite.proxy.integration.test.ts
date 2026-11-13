// @vitest-environment node
import { createServer as createHttpServer } from 'node:http';
import type { IncomingMessage, Server } from 'node:http';
import net from 'node:net';
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

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address() as net.AddressInfo;
      srv.close(() => {
        resolve(port);
      });
    });
  });
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

let stub: Server;
let vite: ViteDevServer;
let base: string;
const seen: Seen[] = [];

beforeAll(async () => {
  const stubPort = await freePort();
  stub = createHttpServer((req, res) => {
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
  await new Promise<void>((resolve) =>
    stub.listen(stubPort, '127.0.0.1', resolve),
  );

  process.env.ANTLAB_API_PORT = String(stubPort);
  vite = await createServer({
    configFile: new URL('./vite.config.ts', import.meta.url).pathname,
    root: new URL('.', import.meta.url).pathname,
    server: { port: await freePort(), strictPort: true },
    logLevel: 'silent',
  });
  await vite.listen();
  base = vite.resolvedUrls?.local[0] ?? '';
  delete process.env.ANTLAB_API_PORT;
});

afterAll(async () => {
  await vite.close();
  if (stub.listening) await new Promise((r) => stub.close(r));
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
    await new Promise((r) => stub.close(r));
    const res = await fetch(new URL('/api/v1/pool', base));
    expect(res.status).toBeGreaterThanOrEqual(500);
    expect(res.headers.get('content-type') ?? '').not.toContain(
      'application/problem+json',
    );
  });
});

// @vitest-environment node
import { describe, expect, it } from 'vitest';
import config from './vite.config.ts';
import { apiProxy } from './vite.proxy.ts';

describe('apiProxy', () => {
  it('defaults to 127.0.0.1:8080 without rewriting or changing origin', () => {
    const proxy = apiProxy({});
    expect(Object.keys(proxy)).toEqual(['/api/']);
    const rule = proxy['/api/'];
    expect(rule).toMatchObject({
      target: 'http://127.0.0.1:8080',
      changeOrigin: false,
    });
    expect(rule).not.toHaveProperty('rewrite');
    expect(rule).not.toHaveProperty('cookieDomainRewrite');
  });

  it('reads ANTLAB_API_PORT', () => {
    expect(apiProxy({ ANTLAB_API_PORT: '18080' })['/api/']).toMatchObject({
      target: 'http://127.0.0.1:18080',
    });
  });

  it.each(['abc', '0', '70000', ' 80', '80 ', '', '1.5', '-1'])(
    'rejects invalid ANTLAB_API_PORT %j',
    (value) => {
      expect(() => apiProxy({ ANTLAB_API_PORT: value })).toThrow(
        /ANTLAB_API_PORT/,
      );
    },
  );
});

describe('vite config', () => {
  it('shares one proxy between dev and preview', () => {
    expect(config.preview?.proxy).toEqual(config.server?.proxy);
  });

  it('binds dev to localhost:5173 only, strictly', () => {
    expect(config.server?.port).toBe(5173);
    expect(config.server?.strictPort).toBe(true);
    expect(config.server?.host).toBe('localhost');
  });
});

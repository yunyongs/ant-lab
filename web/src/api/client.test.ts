import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../test/server.ts';
import { apiClient } from './client.ts';
import { ProblemError, UnreachableError } from './problem.ts';

const problem = (status: number, body: object) =>
  HttpResponse.json(body, {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });

describe('apiClient', () => {
  it('returns the JSON body of a 200 answer', async () => {
    const { data } = await apiClient.GET('/api/v1/pool');
    expect(data?.assets).toHaveLength(2);
  });

  it('requests the same-origin path', async () => {
    let url = '';
    server.use(
      http.get('*/api/v1/pool', ({ request }) => {
        url = request.url;
        return HttpResponse.json({ assets: [] });
      }),
    );
    await apiClient.GET('/api/v1/pool');
    expect(url).toBe(`${window.location.origin}/api/v1/pool`);
  });

  it('throws ProblemError with status and known code', async () => {
    server.use(
      http.get('*/api/v1/pool', () => problem(409, { code: 'SEASON_LIMIT' })),
    );
    const error = await apiClient.GET('/api/v1/pool').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ProblemError);
    expect(error).toMatchObject({ status: 409, code: 'SEASON_LIMIT' });
  });

  it.each([
    ['an unrecognised code', { code: 'NEW_CODE' }],
    ['no code', { title: 'x' }],
    ['a non-string code', { code: 7 }],
  ])('maps a Problem with %s to UNKNOWN', async (_name, body) => {
    server.use(http.get('*/api/v1/pool', () => problem(400, body)));
    const error = await apiClient.GET('/api/v1/pool').catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 400, code: 'UNKNOWN' });
  });

  it.each([
    ['a truncated Problem body', 502, 'application/problem+json', '{"code":'],
    ['a null Problem body', 400, 'application/problem+json', 'null'],
    ['a non-Problem JSON 500', 500, 'application/json', '{"x":1}'],
    [
      'a proxy text 502',
      502,
      'text/plain',
      'Error occurred while trying to proxy',
    ],
  ])('throws UnreachableError for %s', async (_name, status, type, text) => {
    server.use(
      http.get(
        '*/api/v1/pool',
        () =>
          new HttpResponse(text, { status, headers: { 'content-type': type } }),
      ),
    );
    await expect(apiClient.GET('/api/v1/pool')).rejects.toBeInstanceOf(
      UnreachableError,
    );
  });

  it('throws UnreachableError for a non-Problem 502', async () => {
    server.use(
      http.get(
        '*/api/v1/pool',
        () =>
          new HttpResponse('<html></html>', {
            status: 502,
            headers: { 'content-type': 'text/html' },
          }),
      ),
    );
    await expect(apiClient.GET('/api/v1/pool')).rejects.toBeInstanceOf(
      UnreachableError,
    );
  });

  it('throws UnreachableError for a 200 that is not JSON', async () => {
    server.use(
      http.get(
        '*/api/v1/pool',
        () =>
          new HttpResponse('<html></html>', {
            headers: { 'content-type': 'text/html' },
          }),
      ),
    );
    await expect(apiClient.GET('/api/v1/pool')).rejects.toBeInstanceOf(
      UnreachableError,
    );
  });

  it('rejects on a network error', async () => {
    server.use(http.get('*/api/v1/pool', () => HttpResponse.error()));
    await expect(apiClient.GET('/api/v1/pool')).rejects.toBeInstanceOf(Error);
  });
});

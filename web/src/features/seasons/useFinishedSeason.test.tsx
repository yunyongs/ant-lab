import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { problem } from '../../test/authHandlers.ts';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { server } from '../../test/server.ts';
import { useFinishedSeason } from './useFinishedSeason.ts';

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('useFinishedSeason', () => {
  it('goes from loading to finished', async () => {
    server.use(
      http.get('*/api/v1/seasons/7', () =>
        HttpResponse.json(seasonFixture({ id: 7, status: 'FINISHED' })),
      ),
    );
    const { result } = renderHook(() => useFinishedSeason(7), { wrapper });
    expect(result.current.state).toBe('loading');
    await waitFor(() => {
      expect(result.current.state).toBe('finished');
    });
  });

  it.each(['IN_PROGRESS', 'ABANDONING'] as const)(
    'says pending for %s',
    async (status) => {
      server.use(
        http.get('*/api/v1/seasons/7', () =>
          HttpResponse.json(seasonFixture({ id: 7, status })),
        ),
      );
      const { result } = renderHook(() => useFinishedSeason(7), { wrapper });
      await waitFor(() => {
        expect(result.current.state).toBe('pending');
      });
    },
  );

  it('reports an error and retries the season request', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/seasons/7', () => {
        calls += 1;
        return calls === 1
          ? problem(500, { code: 'INTERNAL_ERROR', status: 500 })
          : HttpResponse.json(seasonFixture({ id: 7, status: 'FINISHED' }));
      }),
    );
    const { result } = renderHook(() => useFinishedSeason(7), { wrapper });
    await waitFor(() => {
      expect(result.current.state).toBe('error');
    });
    if (result.current.state === 'error') result.current.retry();
    await waitFor(() => {
      expect(result.current.state).toBe('finished');
    });
    expect(calls).toBe(2);
  });

  it('sends no request without an id', () => {
    const { result } = renderHook(() => useFinishedSeason(null), { wrapper });
    expect(result.current.state).toBe('loading');
  });
});

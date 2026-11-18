import {
  onlineManager,
  QueryClient,
  QueryClientProvider,
} from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { problem } from '../../test/authHandlers.ts';
import { ratingFixture } from '../../test/fixtures/rating.ts';
import { scoreFixture } from '../../test/fixtures/score.ts';
import { server } from '../../test/server.ts';
import { useRating } from '../rating/queries.ts';
import { useScore } from './queries.ts';

// The app client retries a failed read once; these two reads must not, so
// the client here retries aggressively and the hooks must still send one GET.
function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: 3, retryDelay: 1 } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('score and rating queries', () => {
  it('sends one score GET for a 503 even when the client would retry', async () => {
    let gets = 0;
    server.use(
      http.get('*/api/v1/seasons/7/score', () => {
        gets += 1;
        return problem(503, { code: 'ENGINE_UNAVAILABLE', status: 503 });
      }),
    );
    const { result } = renderHook(() => useScore(7, true), { wrapper });
    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(gets).toBe(1);
  });

  it('sends one rating GET for a 500 even when the client would retry', async () => {
    let gets = 0;
    server.use(
      http.get('*/api/v1/members/me/rating', () => {
        gets += 1;
        return problem(500, { code: 'INTERNAL_ERROR', status: 500 });
      }),
    );
    const { result } = renderHook(() => useRating(true), { wrapper });
    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(gets).toBe(1);
  });

  it('sends nothing while disabled', async () => {
    let gets = 0;
    server.use(
      http.get('*/api/v1/seasons/7/score', () => {
        gets += 1;
        return HttpResponse.json({});
      }),
    );
    renderHook(() => useScore(7, false), { wrapper });
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(gets).toBe(0);
  });
});

describe('score and rating queries across mounts', () => {
  function shared() {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: 3, retryDelay: 1 } },
    });
    const wrap = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    return { client, wrap };
  }

  it('C1: mount, unmount, mount sends one score GET while the first is still running', async () => {
    let gets = 0;
    server.use(
      http.get('*/api/v1/seasons/7/score', async () => {
        gets += 1;
        await delay(150);
        return HttpResponse.json(scoreFixture());
      }),
    );
    const { wrap } = shared();
    const first = renderHook(() => useScore(7, true), { wrapper: wrap });
    await waitFor(() => {
      expect(gets).toBe(1);
    });
    first.unmount();
    const second = renderHook(() => useScore(7, true), { wrapper: wrap });
    await waitFor(() => {
      expect(second.result.current.isSuccess).toBe(true);
    });
    expect(gets).toBe(1);
  });

  it('C1: the same holds for the rating', async () => {
    let gets = 0;
    server.use(
      http.get('*/api/v1/members/me/rating', async () => {
        gets += 1;
        await delay(150);
        return HttpResponse.json(ratingFixture());
      }),
    );
    const { wrap } = shared();
    const first = renderHook(() => useRating(true), { wrapper: wrap });
    await waitFor(() => {
      expect(gets).toBe(1);
    });
    first.unmount();
    const second = renderHook(() => useRating(true), { wrapper: wrap });
    await waitFor(() => {
      expect(second.result.current.isSuccess).toBe(true);
    });
    expect(gets).toBe(1);
  });

  it.each<[string, string, () => { isError: boolean }, () => Response]>([
    [
      'score',
      '*/api/v1/seasons/7/score',
      () => useScore(7, true),
      () => problem(503, { code: 'ENGINE_UNAVAILABLE', status: 503 }),
    ],
    [
      'rating',
      '*/api/v1/members/me/rating',
      () => useRating(true),
      () => problem(500, { code: 'INTERNAL_ERROR', status: 500 }),
    ],
  ])(
    'C2: an errored %s is not read again on remount or on reconnect',
    async (_name, path, hook, failure) => {
      let gets = 0;
      server.use(
        http.get(path, () => {
          gets += 1;
          return failure();
        }),
      );
      const { wrap } = shared();
      const first = renderHook(hook, { wrapper: wrap });
      await waitFor(() => {
        expect(first.result.current.isError).toBe(true);
      });
      first.unmount();
      const second = renderHook(hook, { wrapper: wrap });
      await new Promise((resolve) => setTimeout(resolve, 80));
      expect(second.result.current.isError).toBe(true);
      expect(gets).toBe(1);
      act(() => {
        onlineManager.setOnline(false);
        onlineManager.setOnline(true);
      });
      await new Promise((resolve) => setTimeout(resolve, 80));
      expect(gets).toBe(1);
    },
  );
});

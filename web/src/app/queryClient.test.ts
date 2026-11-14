import { useMutation, useQuery } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';
import { ProblemError, UnreachableError } from '../api/problem.ts';
import { createQueryClient, shouldRetryQuery } from './queryClient.ts';

function wrapperFor(client: ReturnType<typeof createQueryClient>) {
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children);
}

describe('production query client', () => {
  it('does not retry a 4xx problem and retries other failures once', () => {
    expect(shouldRetryQuery(0, new ProblemError(404, 'NOT_FOUND'))).toBe(false);
    expect(shouldRetryQuery(0, new ProblemError(409, 'TURN_NOT_REACHED'))).toBe(
      false,
    );
    expect(shouldRetryQuery(0, new ProblemError(500, 'INTERNAL_ERROR'))).toBe(
      true,
    );
    expect(shouldRetryQuery(0, new UnreachableError())).toBe(true);
    expect(shouldRetryQuery(1, new UnreachableError())).toBe(false);
  });

  it('fetches a 404 query once and an unreachable query twice', async () => {
    const client = createQueryClient();
    client.setDefaultOptions({
      queries: {
        ...client.getDefaultOptions().queries,
        retryDelay: 1,
      },
    });
    let notFound = 0;
    let unreachable = 0;
    const missing = renderHook(
      () =>
        useQuery({
          queryKey: ['missing'],
          queryFn: () => {
            notFound += 1;
            return Promise.reject(new ProblemError(404, 'NOT_FOUND'));
          },
        }),
      { wrapper: wrapperFor(client) },
    );
    await waitFor(() => {
      expect(missing.result.current.isError).toBe(true);
    });
    const down = renderHook(
      () =>
        useQuery({
          queryKey: ['down'],
          queryFn: () => {
            unreachable += 1;
            return Promise.reject(new UnreachableError());
          },
        }),
      { wrapper: wrapperFor(client) },
    );
    await waitFor(() => {
      expect(down.result.current.isError).toBe(true);
    });
    expect(notFound).toBe(1);
    expect(unreachable).toBe(2);
  });

  it('never retries a mutation', async () => {
    const client = createQueryClient();
    let calls = 0;
    const { result } = renderHook(
      () =>
        useMutation({
          mutationFn: () => {
            calls += 1;
            return Promise.reject(new UnreachableError());
          },
        }),
      { wrapper: wrapperFor(client) },
    );
    result.current.mutate();
    await waitFor(() => {
      expect(result.current.isError).toBe(true);
    });
    expect(calls).toBe(1);
  });
});

import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { createQueryClient } from '../../app/queryClient.ts';
import { setSession } from '../auth/session.ts';
import {
  engineUnavailable503,
  serveProjection,
} from '../../test/serveProjection.ts';
import { projectionKey, useProjection } from './queries.ts';

describe('projectionKey', () => {
  it('carries the turn only for the current team', () => {
    expect(projectionKey(7, 'CURRENT', 4)).toEqual([
      'projection',
      7,
      'CURRENT',
      4,
    ]);
    expect(projectionKey(7, 'START', 4)).toEqual([
      'projection',
      7,
      'START',
      null,
    ]);
    expect(projectionKey(7, 'END', 12)).toEqual(['projection', 7, 'END', null]);
  });
});

describe('useProjection', () => {
  it('P6: with the production client a failing engine is asked once, not twice', async () => {
    setSession('test-access-1');
    const world = serveProjection({ answer: engineUnavailable503 });
    const client = createQueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    const { result } = renderHook(() => useProjection(7, 'CURRENT', 4, true), {
      wrapper,
    });
    await waitFor(
      () => {
        expect(result.current.isError).toBe(true);
      },
      { timeout: 3000 },
    );
    expect(world.phases).toEqual(['CURRENT']);
  });

  it('sends no request while disabled', async () => {
    setSession('test-access-1');
    const world = serveProjection();
    const client = createQueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );
    renderHook(() => useProjection(7, 'CURRENT', 4, false), { wrapper });
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(world.phases).toEqual([]);
  });
});

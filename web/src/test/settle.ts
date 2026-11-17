import type { QueryClient } from '@tanstack/react-query';
import { waitFor } from '@testing-library/react';
import { expect } from 'vitest';

// Waits until no query is in flight. A query that is enabled starts fetching
// while the render is flushed, so "nothing was requested" can be asserted
// after this without a fixed delay.
export async function settle(client: QueryClient): Promise<void> {
  await waitFor(() => {
    expect(client.isFetching()).toBe(0);
  });
}

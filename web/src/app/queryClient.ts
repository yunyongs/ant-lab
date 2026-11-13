import { QueryClient } from '@tanstack/react-query';
import { ProblemError } from '../api/problem.ts';

// A 4xx answer is an expected result (unknown turn, missing season): asking
// again cannot change it. Network and server failures get one retry.
export function shouldRetryQuery(count: number, error: unknown): boolean {
  if (
    error instanceof ProblemError &&
    error.status >= 400 &&
    error.status < 500
  ) {
    return false;
  }
  return count < 1;
}

// Mutations keep the default of zero retries: a POST (order, turn advance)
// must never be repeated automatically.
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetryQuery, refetchOnWindowFocus: false },
    },
  });
}

export const queryClient = createQueryClient();

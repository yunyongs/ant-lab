import { QueryClient } from '@tanstack/react-query';

// Mutations keep the default of zero retries: a POST (order, turn advance)
// must never be repeated automatically.
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: 1, refetchOnWindowFocus: false },
    },
  });
}

export const queryClient = createQueryClient();

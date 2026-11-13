import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { StrictMode, type ReactElement } from 'react';
import { MemoryRouter, RouterProvider } from 'react-router';
import { createTestRouter } from '../app/router.tsx';
import { AuthProvider } from '../features/auth/AuthProvider.tsx';
import { clearSession, setSession } from '../features/auth/session.ts';

function createTestQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

export type TestSession = 'authenticated' | 'anonymous' | 'restoring';

// Seeds the session store directly, so no network call is made. 'restoring'
// is the real start: the provider then runs the one restore refresh.
function seedSession(session: TestSession) {
  if (session === 'authenticated') setSession('test-access-1');
  else if (session === 'anonymous') clearSession();
}

export function renderWithProviders(
  ui: ReactElement,
  route = '/',
  session: TestSession = 'authenticated',
) {
  seedSession(session);
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

export function renderRoute(
  route: string,
  options: { session?: TestSession; strict?: boolean } = {},
) {
  seedSession(options.session ?? 'authenticated');
  const queryClient = createTestQueryClient();
  const router = createTestRouter([route]);
  const tree = (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  );
  return {
    ...render(options.strict ? <StrictMode>{tree}</StrictMode> : tree),
    queryClient,
    router,
  };
}

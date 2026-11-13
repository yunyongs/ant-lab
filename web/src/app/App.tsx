import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router/dom';
import { AuthProvider } from '../features/auth/AuthProvider.tsx';
import { queryClient } from './queryClient.ts';
import { createAppRouter } from './router.tsx';

const router = createAppRouter();

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  );
}

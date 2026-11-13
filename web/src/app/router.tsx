import { createBrowserRouter, createMemoryRouter } from 'react-router';
import type { RouteObject } from 'react-router';
import { PublicOnly } from '../features/auth/PublicOnly.tsx';
import { RequireAuth } from '../features/auth/RequireAuth.tsx';
import { HomePage } from '../pages/HomePage.tsx';
import { LoginPage } from '../pages/LoginPage.tsx';
import { NotFoundPage } from '../pages/NotFoundPage.tsx';
import { SignupPage } from '../pages/SignupPage.tsx';
import { AppShell } from './AppShell.tsx';
import { PublicLayout } from './PublicLayout.tsx';
import { RouteError } from './RouteError.tsx';

export const routes: RouteObject[] = [
  {
    element: <PublicOnly />,
    errorElement: <RouteError />,
    children: [
      {
        element: <PublicLayout />,
        children: [
          { path: '/login', element: <LoginPage /> },
          { path: '/signup', element: <SignupPage /> },
        ],
      },
    ],
  },
  {
    element: <RequireAuth />,
    errorElement: <RouteError />,
    children: [
      {
        path: '/',
        element: <AppShell />,
        children: [
          { index: true, element: <HomePage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];

export const createAppRouter = () => createBrowserRouter(routes);

export const createTestRouter = (initialEntries: string[]) =>
  createMemoryRouter(routes, { initialEntries });

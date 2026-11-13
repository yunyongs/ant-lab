import { createBrowserRouter, createMemoryRouter } from 'react-router';
import type { RouteObject } from 'react-router';
import { PublicOnly } from '../features/auth/PublicOnly.tsx';
import { RequireAuth } from '../features/auth/RequireAuth.tsx';
import { LoginPage } from '../pages/LoginPage.tsx';
import { NewSeasonPage } from '../pages/NewSeasonPage.tsx';
import { NotFoundPage } from '../pages/NotFoundPage.tsx';
import { SeasonPage } from '../pages/SeasonPage.tsx';
import { SeasonsPage } from '../pages/SeasonsPage.tsx';
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
          { index: true, element: <SeasonsPage /> },
          { path: 'seasons/new', element: <NewSeasonPage /> },
          { path: 'seasons/:seasonId', element: <SeasonPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
];

export const createAppRouter = () => createBrowserRouter(routes);

export const createTestRouter = (initialEntries: string[]) =>
  createMemoryRouter(routes, { initialEntries });

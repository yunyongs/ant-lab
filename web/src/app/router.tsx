import { createBrowserRouter, createMemoryRouter } from 'react-router';
import type { RouteObject } from 'react-router';
import { HomePage } from '../pages/HomePage.tsx';
import { NotFoundPage } from '../pages/NotFoundPage.tsx';
import { AppShell } from './AppShell.tsx';
import { RouteError } from './RouteError.tsx';

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    errorElement: <RouteError />,
    children: [
      { index: true, element: <HomePage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

export const createAppRouter = () => createBrowserRouter(routes);

export const createTestRouter = (initialEntries: string[]) =>
  createMemoryRouter(routes, { initialEntries });

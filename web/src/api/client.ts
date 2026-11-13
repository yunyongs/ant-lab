import createClient from 'openapi-fetch';
import { authFetch } from '../features/auth/authFetch.ts';
import { classify } from './authClient.ts';
import type { paths } from './schema';

// Every data call goes through this client: it carries the bearer token and
// refreshes once on a 401.
export const apiClient = createClient<paths>({
  baseUrl: window.location.origin,
  fetch: (request) => authFetch(request),
});
apiClient.use(classify);

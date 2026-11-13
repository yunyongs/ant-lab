import { http, HttpResponse } from 'msw';

// Plan-approved mocks of the auth endpoints, shaped like docs/api/openapi.yaml.
// Tokens are plain fixture strings, not JWT-shaped.
export const REFRESH_COOKIE = 'antlab_refresh=fixture; Path=/api/v1/auth';

export const problem = (status: number, body: object) =>
  HttpResponse.json(body, {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });

export const accessToken = (token: string) =>
  HttpResponse.json({
    accessToken: token,
    tokenType: 'Bearer',
    expiresIn: 900,
  });

export const authHandlers = [
  http.post('*/api/v1/auth/refresh', () =>
    problem(401, { code: 'UNAUTHENTICATED', status: 401 }),
  ),
  http.post(
    '*/api/v1/auth/logout',
    () => new HttpResponse(null, { status: 204 }),
  ),
  http.post('*/api/v1/auth/login', () =>
    problem(401, { code: 'INVALID_CREDENTIALS', status: 401 }),
  ),
  http.post('*/api/v1/auth/signup', () =>
    problem(409, { code: 'EMAIL_TAKEN', status: 409 }),
  ),
];

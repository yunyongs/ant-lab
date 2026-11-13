import createClient, { type Middleware } from 'openapi-fetch';
import { errorMessages } from './errorMessages.ts';
import { ProblemError, UnreachableError, type ErrorCode } from './problem.ts';
import type { paths } from './schema';

const PROBLEM_TYPE = 'application/problem+json';
const NO_CONTENT = 204;

function isKnownCode(value: unknown): value is ErrorCode {
  return typeof value === 'string' && Object.hasOwn(errorMessages, value);
}

async function problemCode(response: Response): Promise<ErrorCode | 'UNKNOWN'> {
  let body: unknown;
  try {
    body = await response.clone().json();
  } catch {
    throw new UnreachableError();
  }
  if (typeof body !== 'object' || body === null) throw new UnreachableError();
  const code = (body as { code?: unknown }).code;
  return isKnownCode(code) ? code : 'UNKNOWN';
}

// Classified by content type, not status: a dev proxy answers 5xx with
// HTML or text when the API is down, which is not an API answer.
const classify: Middleware = {
  async onResponse({ response }) {
    const type = response.headers.get('content-type') ?? '';
    if (type.includes(PROBLEM_TYPE)) {
      throw new ProblemError(response.status, await problemCode(response));
    }
    if (!response.ok) throw new UnreachableError();
    if (response.status !== NO_CONTENT && !type.includes('application/json')) {
      throw new UnreachableError();
    }
    return undefined;
  },
};

export const apiClient = createClient<paths>({
  baseUrl: window.location.origin,
  fetch: (request) => globalThis.fetch(request),
});
apiClient.use(classify);

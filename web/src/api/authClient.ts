import createClient, { type Middleware } from 'openapi-fetch';
import { errorMessages } from './errorMessages.ts';
import {
  ProblemError,
  UnreachableError,
  type ErrorCode,
  type FieldViolation,
} from './problem.ts';
import type { paths } from './schema';

const PROBLEM_TYPE = 'application/problem+json';
const JSON_TYPE = 'application/json';
const NO_CONTENT = 204;

function isKnownCode(value: unknown): value is ErrorCode {
  return typeof value === 'string' && Object.hasOwn(errorMessages, value);
}

function isViolation(value: unknown): value is FieldViolation {
  if (typeof value !== 'object' || value === null) return false;
  const { field, reason } = value as { field?: unknown; reason?: unknown };
  return typeof field === 'string' && typeof reason === 'string';
}

function violationsOf(body: object): FieldViolation[] {
  const errors = (body as { errors?: unknown }).errors;
  if (!Array.isArray(errors)) return [];
  return errors.every(isViolation) ? errors : [];
}

async function problemOf(response: Response): Promise<ProblemError> {
  let body: unknown;
  try {
    body = await response.clone().json();
  } catch {
    throw new UnreachableError();
  }
  if (typeof body !== 'object' || body === null) throw new UnreachableError();
  const code = (body as { code?: unknown }).code;
  return new ProblemError(
    response.status,
    isKnownCode(code) ? code : 'UNKNOWN',
    violationsOf(body),
  );
}

// Exact media-type comparison: a substring match would accept a text/html
// answer that merely mentions application/problem+json in a parameter.
function mediaType(response: Response): string {
  const header = response.headers.get('content-type') ?? '';
  return (header.split(';')[0] ?? '').trim().toLowerCase();
}

// Classified by content type, not status: a dev proxy answers 5xx with
// HTML or text when the API is down, which is not an API answer.
export const classify: Middleware = {
  async onResponse({ response }) {
    const type = mediaType(response);
    if (type === PROBLEM_TYPE) throw await problemOf(response);
    if (!response.ok) throw new UnreachableError();
    if (response.status !== NO_CONTENT && type !== JSON_TYPE) {
      throw new UnreachableError();
    }
    return undefined;
  },
};

// Auth endpoints: no bearer token and no refresh-and-replay.
export const authClient = createClient<paths>({
  baseUrl: window.location.origin,
  fetch: (request) => globalThis.fetch(request),
});
authClient.use(classify);

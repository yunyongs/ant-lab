import { refreshSession } from './refresh.ts';
import { clearSession, getAccessToken, getStatus } from './session.ts';

const AUTH_PATH = '/api/v1/auth/';

function withBearer(request: Request, token: string | null): Request {
  if (token !== null) request.headers.set('Authorization', `Bearer ${token}`);
  return request;
}

// Replay happens only after a 401. The security filter answers it before any
// handler runs, so the request had no effect and a POST cannot apply twice.
export async function authFetch(request: Request): Promise<Response> {
  if (new URL(request.url).pathname.startsWith(AUTH_PATH)) {
    return globalThis.fetch(request);
  }
  const replay = request.clone();
  const sentToken = getAccessToken();
  const first = await globalThis.fetch(withBearer(request, sentToken));
  if (first.status !== 401 || getStatus() === 'anonymous') return first;

  // Another call may have refreshed while this one was in flight.
  const current = getAccessToken();
  if (current === null || current === sentToken) {
    if (!(await refreshSession())) return first;
  }

  const second = await globalThis.fetch(withBearer(replay, getAccessToken()));
  if (second.status === 401) clearSession('expired');
  return second;
}

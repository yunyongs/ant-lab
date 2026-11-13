import { authClient } from '../../api/authClient.ts';
import { withAuthLock } from './authLock.ts';
import { clearSession, getStatus, setSession } from './session.ts';

let inFlight: Promise<boolean> | null = null;

async function rotate(): Promise<boolean> {
  // A logout (or a failed refresh) that ran while this call waited for the
  // lock wins: send nothing, and keep the notice that is already set.
  if (getStatus() === 'anonymous') return false;
  try {
    const { data } = await authClient.POST('/api/v1/auth/refresh');
    // The session ended while the request was in flight: drop the token.
    if (getStatus() === 'anonymous') return false;
    if (typeof data?.accessToken === 'string' && data.accessToken !== '') {
      setSession(data.accessToken);
      return true;
    }
  } catch {
    // Rejected, unreachable or malformed: all mean there is no session.
  }
  // Only a session that was live shows the "login ended" notice; a failed
  // restore at page load is simply a visitor who has not logged in.
  clearSession(getStatus() === 'authenticated' ? 'expired' : null);
  return false;
}

// One refresh at a time per tab; the lock covers the other tabs. The lock is
// held until the response headers (and so the rotated cookie) have arrived.
export function refreshSession(): Promise<boolean> {
  inFlight ??= withAuthLock(rotate).finally(() => {
    inFlight = null;
  });
  return inFlight;
}

// Called from an effect, so StrictMode runs it twice. The status guard plus
// the shared in-flight promise keep that to one request: a second refresh
// would present an already rotated cookie, which the server treats as theft
// and ends the login.
export function restoreSession(): Promise<void> {
  if (getStatus() !== 'restoring') return Promise.resolve();
  return refreshSession().then(() => undefined);
}

export function resetRefreshForTests(): void {
  inFlight = null;
}

import { authClient } from '../../api/authClient.ts';
import {
  ProblemError,
  type ErrorCode,
  type FieldViolation,
} from '../../api/problem.ts';
import { withAuthLock } from './authLock.ts';
import { announceLogout } from './logoutBroadcast.ts';
import { clearSession, setNotice, setSession } from './session.ts';

export type AuthFailure =
  | {
      kind: 'problem';
      status: number;
      code: ErrorCode | 'UNKNOWN';
      errors: readonly FieldViolation[];
    }
  | { kind: 'unreachable' };

export type AuthResult =
  | { ok: true }
  | { ok: false; failure: AuthFailure }
  // Signup worked but the login that follows did not.
  | { ok: false; signedUpOnly: true };

export interface SignupInput {
  email: string;
  name: string;
  password: string;
}

function failureOf(error: unknown): AuthFailure {
  if (error instanceof ProblemError) {
    return {
      kind: 'problem',
      status: error.status,
      code: error.code,
      errors: error.errors,
    };
  }
  return { kind: 'unreachable' };
}

export function login(email: string, password: string): Promise<AuthResult> {
  return withAuthLock(async (): Promise<AuthResult> => {
    try {
      const { data } = await authClient.POST('/api/v1/auth/login', {
        body: { email, password },
      });
      if (typeof data?.accessToken !== 'string' || data.accessToken === '') {
        return { ok: false, failure: { kind: 'unreachable' } };
      }
      setSession(data.accessToken);
      return { ok: true };
    } catch (error) {
      return { ok: false, failure: failureOf(error) };
    }
  });
}

export async function signupAndLogin(input: SignupInput): Promise<AuthResult> {
  try {
    await authClient.POST('/api/v1/auth/signup', { body: input });
  } catch (error) {
    return { ok: false, failure: failureOf(error) };
  }
  const result = await login(input.email, input.password);
  if (result.ok) return result;
  setNotice('signedUp');
  return { ok: false, signedUpOnly: true };
}

// Always finishes locally, whatever the server answers: the player asked to
// leave. The session is cleared inside the lock, so a refresh queued behind
// this logout cannot revive it.
export async function logout(): Promise<void> {
  await withAuthLock(async () => {
    try {
      await authClient.POST('/api/v1/auth/logout');
    } catch {
      // Network error or 5xx: the local logout below still happens.
    }
    clearSession();
  });
  announceLogout();
}

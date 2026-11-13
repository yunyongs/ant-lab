import { useSyncExternalStore } from 'react';
import { login, logout, signupAndLogin } from './authApi.ts';
import {
  getNotice,
  getStatus,
  subscribe,
  type SessionNotice,
  type SessionStatus,
} from './session.ts';

export function useAuth(): {
  status: SessionStatus;
  notice: SessionNotice | null;
  login: typeof login;
  signup: typeof signupAndLogin;
  logout: typeof logout;
} {
  const status = useSyncExternalStore(subscribe, getStatus);
  const notice = useSyncExternalStore(subscribe, getNotice);
  return { status, notice, login, signup: signupAndLogin, logout };
}

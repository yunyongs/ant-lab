export type SessionStatus = 'restoring' | 'authenticated' | 'anonymous';
export type SessionNotice = 'expired' | 'otherTab' | 'signedUp';

// The access token lives in this module variable only: not in React state,
// context, storage, the query cache or a URL. Screens read the status.
let token: string | null = null;
let status: SessionStatus = 'restoring';
let notice: SessionNotice | null = null;
const listeners = new Set<() => void>();

function emit() {
  for (const listener of [...listeners]) listener();
}

export function getAccessToken(): string | null {
  return token;
}

export function getStatus(): SessionStatus {
  return status;
}

export function getNotice(): SessionNotice | null {
  return notice;
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function setSession(accessToken: string): void {
  token = accessToken;
  status = 'authenticated';
  notice = null;
  emit();
}

export function clearSession(next: SessionNotice | null = null): void {
  token = null;
  status = 'anonymous';
  notice = next;
  emit();
}

export function setNotice(next: SessionNotice | null): void {
  notice = next;
  emit();
}

export function resetSessionForTests(): void {
  token = null;
  status = 'restoring';
  notice = null;
}

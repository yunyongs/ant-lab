import { useQueryClient } from '@tanstack/react-query';
import { useEffect, type ReactNode } from 'react';
import { onLogoutFromOtherTab } from './logoutBroadcast.ts';
import { restoreSession } from './refresh.ts';
import { clearSession } from './session.ts';
import { useAuth } from './useAuth.ts';

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const { status } = useAuth();

  useEffect(() => {
    void restoreSession();
  }, []);

  useEffect(
    () =>
      onLogoutFromOtherTab(() => {
        clearSession('otherTab');
      }),
    [],
  );

  // Cached server data belongs to the login that ended.
  useEffect(() => {
    if (status === 'anonymous') queryClient.clear();
  }, [status, queryClient]);

  return children;
}

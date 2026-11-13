import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from './useAuth.ts';

export function RequireAuth() {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'restoring') {
    return (
      <main>
        <p role="status">로그인 상태를 확인하는 중입니다</p>
      </main>
    );
  }
  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }
  return <Outlet />;
}

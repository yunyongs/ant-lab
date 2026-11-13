import { Navigate, Outlet, useLocation } from 'react-router';
import { returnTo } from './returnTo.ts';
import { useAuth } from './useAuth.ts';

export function PublicOnly() {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'restoring') {
    return (
      <main>
        <p role="status">로그인 상태를 확인하는 중입니다</p>
      </main>
    );
  }
  if (status === 'authenticated') {
    return <Navigate to={returnTo(location.state)} replace />;
  }
  return <Outlet />;
}

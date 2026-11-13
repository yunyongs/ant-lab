import { Outlet, useLocation } from 'react-router';
import { ApiStatus } from '../features/health/ApiStatus.tsx';
import { APP_NAME } from './appName.ts';
import styles from './PublicLayout.module.css';

export function PublicLayout() {
  const { pathname } = useLocation();
  const context =
    pathname === '/signup' ? '계정 만들기' : '로그인이 필요합니다';
  return (
    <div className={styles.layout}>
      <header className={styles.header}>
        <div className={styles.inner}>
          <span className={styles.title}>{APP_NAME}</span>
          <div className={styles.row}>
            <span className={styles.context}>{context}</span>
            <ApiStatus />
          </div>
        </div>
      </header>
      <main className={styles.main}>
        <div className={styles.inner}>
          <Outlet />
        </div>
      </main>
    </div>
  );
}

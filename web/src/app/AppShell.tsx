import { NavLink, Outlet } from 'react-router';
import { useAuth } from '../features/auth/useAuth.ts';
import { APP_NAME } from './appName.ts';
import styles from './AppShell.module.css';

export interface NavItem {
  to: string;
  label: string;
}

const NAV_ITEMS: NavItem[] = [{ to: '/', label: '홈' }];

export function AppShell({ navItems = NAV_ITEMS }: { navItems?: NavItem[] }) {
  const { logout } = useAuth();
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <div className={`${styles.inner} ${styles.headerRow}`}>
          <span className={styles.title}>{APP_NAME}</span>
          <button
            type="button"
            className={styles.logout}
            onClick={() => {
              void logout();
            }}
          >
            로그아웃
          </button>
        </div>
      </header>
      <main className={styles.main}>
        <div className={styles.inner}>
          <Outlet />
        </div>
      </main>
      <nav className={styles.nav} aria-label="주 메뉴">
        <ul className={styles.navList}>
          {navItems.map((item) => (
            <li key={item.to} className={styles.navItem}>
              <NavLink
                to={item.to}
                end
                className={({ isActive }) =>
                  `${styles.navLink} ${isActive ? styles.active : ''}`
                }
              >
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
    </div>
  );
}

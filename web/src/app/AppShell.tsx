import { NavLink, Outlet } from 'react-router';
import { APP_NAME } from './appName.ts';
import styles from './AppShell.module.css';

export interface NavItem {
  to: string;
  label: string;
}

const NAV_ITEMS: NavItem[] = [{ to: '/', label: '홈' }];

export function AppShell({ navItems = NAV_ITEMS }: { navItems?: NavItem[] }) {
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <div className={styles.inner}>
          <span className={styles.title}>{APP_NAME}</span>
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

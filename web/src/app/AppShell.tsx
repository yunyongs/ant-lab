import { Link, Outlet, useLocation, useMatch } from 'react-router';
import { useAuth } from '../features/auth/useAuth.ts';
import { ApiStatus } from '../features/health/ApiStatus.tsx';
import { MAX_ROUTE_ID, parseWholeNumber } from '../features/format/integer.ts';
import { headerSeasonLine } from '../features/seasons/labels.ts';
import { useSeason } from '../features/seasons/queries.ts';
import { APP_NAME } from './appName.ts';
import { NAV_CONFIG, type NavConfig } from './navItems.ts';
import styles from './AppShell.module.css';

function useRouteSeasonId(): number | null {
  const match = useMatch('/seasons/:seasonId/*');
  const text = match?.params.seasonId;
  return text === undefined ? null : parseWholeNumber(text, MAX_ROUTE_ID);
}

function SeasonLine({ id }: { id: number | null }) {
  // Same query key as the season page: one request for both.
  const { data } = useSeason(id);
  if (id === null || data === undefined) return <span />;
  return <span className={styles.seasonLine}>{headerSeasonLine(data)}</span>;
}

function navTarget(
  item: NavConfig,
  seasonId: number | null,
  pathname: string,
): { to: string; active: boolean } | null {
  if (!item.enabled) return null;
  if (item.key === 'seasons') {
    return {
      to: '/',
      active: pathname === '/' || pathname === '/seasons/new',
    };
  }
  if (item.key === 'turn' && seasonId !== null) {
    const to = `/seasons/${String(seasonId)}`;
    return {
      to,
      active: pathname === to || pathname.startsWith(`${to}/turns/`),
    };
  }
  if (item.key === 'trade' && seasonId !== null) {
    const to = `/seasons/${String(seasonId)}/trade`;
    return { to, active: pathname === to };
  }
  return null;
}

export function AppShell() {
  const { logout } = useAuth();
  const seasonId = useRouteSeasonId();
  const { pathname } = useLocation();
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
        <div className={`${styles.inner} ${styles.statusRow}`}>
          <SeasonLine id={seasonId} />
          <ApiStatus />
        </div>
      </header>
      <main className={styles.main}>
        <div className={styles.inner}>
          <Outlet />
        </div>
      </main>
      <nav className={styles.nav} aria-label="주 메뉴">
        <ul className={styles.navList}>
          {NAV_CONFIG.map((item) => {
            const target = navTarget(item, seasonId, pathname);
            return (
              <li key={item.key} className={styles.navItem}>
                {target ? (
                  <Link
                    to={target.to}
                    aria-current={target.active ? 'page' : undefined}
                    className={`${styles.navLink} ${target.active ? styles.active : ''}`}
                  >
                    {item.label}
                  </Link>
                ) : (
                  <span
                    aria-disabled="true"
                    className={`${styles.navLink} ${styles.disabled}`}
                  >
                    {item.label}
                  </span>
                )}
              </li>
            );
          })}
        </ul>
      </nav>
    </div>
  );
}

import { useEffect, useRef, useState } from 'react';
import { useIsMutating } from '@tanstack/react-query';
import { Link, Outlet, useLocation, useMatch, useNavigate } from 'react-router';
import {
  MENU_ABANDON_ITEM,
  MENU_BUTTON_ID,
} from '../features/abandon/abandonMessages.ts';
import { abandonMode } from '../features/abandon/abandonMode.ts';
import { useAuth } from '../features/auth/useAuth.ts';
import { ConventionToggle } from '../features/format/ConventionToggle.tsx';
import { ApiStatus } from '../features/health/ApiStatus.tsx';
import { MAX_ROUTE_ID, parseWholeNumber } from '../features/format/integer.ts';
import { headerSeasonLine, type Season } from '../features/seasons/labels.ts';
import { useSeason, useSeasons } from '../features/seasons/queries.ts';
import { canChangeFormation } from '../features/seasons/seasonGate.ts';
import { advanceKey } from '../features/turn/queries.ts';
import { APP_NAME } from './appName.ts';
import { NAV_CONFIG, type NavConfig } from './navItems.ts';
import styles from './AppShell.module.css';

const NAV_DISABLED_REASON = '시즌을 열면 쓸 수 있습니다';

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
  linkSeasonId: number | null,
): { to: string; active: boolean } | null {
  if (!item.enabled) return null;
  if (item.key === 'seasons') {
    return {
      to: '/',
      active: pathname === '/' || pathname === '/seasons/new',
    };
  }
  if (item.key === 'turn' && linkSeasonId !== null) {
    const to = `/seasons/${String(linkSeasonId)}`;
    return {
      to,
      active: pathname === to || pathname.startsWith(`${to}/turns/`),
    };
  }
  if (item.key === 'trade' && linkSeasonId !== null) {
    const to = `/seasons/${String(linkSeasonId)}/trade`;
    return {
      to,
      active:
        pathname === to ||
        pathname === `/seasons/${String(linkSeasonId)}/formation`,
    };
  }
  if (item.key === 'report' && seasonId !== null) {
    const to = `/seasons/${String(seasonId)}/report`;
    return { to, active: pathname === to };
  }
  if (item.key === 'review' && seasonId !== null) {
    const to = `/seasons/${String(seasonId)}/review`;
    return { to, active: pathname === to };
  }
  return null;
}

// Outside a season the turn and trade items open the one season in
// progress; with none or several the player picks one from the list.
function useSoleActiveSeasonId(): number | null {
  const { data, status } = useSeasons();
  // A failed refetch (status error) turns the links off even with cached data.
  if (status !== 'success') return null;
  const active = data.filter((item) => item.status === 'IN_PROGRESS');
  const [only] = active;
  return active.length === 1 && only !== undefined ? only.id : null;
}

function BottomNav({
  seasonId,
  linkSeasonId,
}: {
  seasonId: number | null;
  linkSeasonId: number | null;
}) {
  const { pathname } = useLocation();
  const anyDisabled = NAV_CONFIG.some(
    (item) => navTarget(item, seasonId, pathname, linkSeasonId) === null,
  );
  return (
    <nav className={styles.nav} aria-label="주 메뉴">
      {anyDisabled && (
        <p id="nav-disabled-reason" className={styles.navHint}>
          {NAV_DISABLED_REASON}
        </p>
      )}
      <ul className={styles.navList}>
        {NAV_CONFIG.map((item) => {
          const target = navTarget(item, seasonId, pathname, linkSeasonId);
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
                  aria-describedby="nav-disabled-reason"
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
  );
}

// The season list is read on the list page only, where its own query is
// already running (no request on season routes or the new-season form).
function ListPageNav() {
  return <BottomNav seasonId={null} linkSeasonId={useSoleActiveSeasonId()} />;
}

function usesWideContent(
  pathname: string,
  seasonId: number | null,
  currentTurnNo: number | undefined,
): boolean {
  if (seasonId === null) return false;
  const base = `/seasons/${String(seasonId)}`;
  if (pathname === `${base}/trade` || pathname.startsWith(`${base}/turns/`)) {
    return true;
  }
  return pathname === base && (currentTurnNo ?? 0) >= 1;
}

// Reads no query itself: a late-mounted observer of a stale season would
// refetch it. The header menu reads the season while the header is mounted.
function SeasonMenuItems({
  seasonId,
  data,
  close,
}: {
  seasonId: number;
  data: Season | undefined;
  close: () => void;
}) {
  const navigate = useNavigate();
  const advancing = useIsMutating({ mutationKey: advanceKey(seasonId) }) > 0;
  if (data === undefined) return null;
  const base = `/seasons/${String(seasonId)}`;
  return (
    <>
      {canChangeFormation(data) && (
        <Link
          to={`${base}/formation`}
          className={styles.menuItem}
          onClick={close}
        >
          포메이션 바꾸기
        </Link>
      )}
      {abandonMode(data) === 'start' && (
        <button
          type="button"
          className={`${styles.menuItem} ${styles.menuDanger}`}
          disabled={advancing}
          onClick={() => {
            close();
            void navigate(base, { state: { openAbandon: true } });
          }}
        >
          {MENU_ABANDON_ITEM}
        </button>
      )}
    </>
  );
}

function HeaderMenu({ seasonId }: { seasonId: number | null }) {
  const { logout } = useAuth();
  const { data: season } = useSeason(seasonId);
  const [open, setOpen] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      button.current?.focus();
    };
    const onPointer = (event: PointerEvent) => {
      if (!(event.target instanceof Node)) return;
      const root = wrap.current;
      if (root === null || root.contains(event.target)) return;
      const hadFocus = root.contains(document.activeElement);
      setOpen(false);
      // The tap may move focus to the tapped control; only a lost focus
      // (body) returns to the menu button.
      if (hadFocus)
        window.setTimeout(() => {
          if (document.activeElement === document.body) button.current?.focus();
        }, 0);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('pointerdown', onPointer);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('pointerdown', onPointer);
    };
  }, [open]);
  return (
    <div ref={wrap} className={styles.menuWrap}>
      <button
        ref={button}
        id={MENU_BUTTON_ID}
        type="button"
        className={styles.menuButton}
        aria-label="메뉴"
        aria-expanded={open}
        aria-controls="header-menu"
        onClick={() => {
          setOpen((value) => !value);
        }}
      >
        <span className={styles.bars} aria-hidden="true" />
      </button>
      {open && (
        <div id="header-menu" className={styles.menu}>
          <ConventionToggle className={styles.menuItem} />
          {seasonId !== null && (
            <SeasonMenuItems
              seasonId={seasonId}
              data={season}
              close={() => {
                setOpen(false);
              }}
            />
          )}
          <button
            type="button"
            className={styles.menuItem}
            onClick={() => {
              void logout();
            }}
          >
            로그아웃
          </button>
        </div>
      )}
    </div>
  );
}

export function AppShell() {
  const seasonId = useRouteSeasonId();
  const { pathname } = useLocation();
  const { data: season } = useSeason(seasonId);
  const wideContent = usesWideContent(
    pathname,
    seasonId,
    season?.currentTurnNo,
  );
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <div
          className={`${styles.inner} ${styles.headerRow} ${wideContent ? styles.wide : ''}`}
        >
          <Link to="/" className={styles.title}>
            {APP_NAME}
          </Link>
          <Link to="/leaderboard" className={styles.leaderboardLink}>
            리더보드
          </Link>
          <ApiStatus compact />
          <HeaderMenu seasonId={seasonId} />
        </div>
        {seasonId !== null && (
          <div className={`${styles.inner} ${wideContent ? styles.wide : ''}`}>
            <SeasonLine id={seasonId} />
          </div>
        )}
      </header>
      <main className={styles.main}>
        <div className={`${styles.inner} ${wideContent ? styles.wide : ''}`}>
          <Outlet />
        </div>
      </main>
      {seasonId !== null ? (
        <BottomNav seasonId={seasonId} linkSeasonId={seasonId} />
      ) : pathname === '/' ? (
        <ListPageNav />
      ) : (
        <BottomNav seasonId={null} linkSeasonId={null} />
      )}
    </div>
  );
}

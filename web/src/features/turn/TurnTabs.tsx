import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { ContributionPanel } from '../contribution/ContributionSection.tsx';
import type { Season } from '../seasons/labels.ts';
import {
  ValueChartPanel,
  ValueTablePanel,
} from '../valueline/ValueSection.tsx';
import { TurnDetailRows } from './TurnDetailRows.tsx';
import type { TurnResult } from './queries.ts';
import styles from './TurnTabs.module.css';

const TABS = [
  { key: 'chart', label: '그래프' },
  { key: 'contribution', label: '기여' },
  { key: 'table', label: '표' },
] as const;

type TabKey = (typeof TABS)[number]['key'];

// The three views of one turn. WAI-ARIA tabs: arrow keys move and select,
// Home/End jump, only the selected tab is in the tab order, and only its
// panel is mounted. A chart failure stays inside the chart panel.
export function TurnTabs({
  season,
  turn,
}: {
  season: Season;
  turn: TurnResult;
}) {
  const uid = useId();
  const [selected, setSelected] = useState<TabKey>('chart');
  const buttons = useRef(new Map<TabKey, HTMLButtonElement>());
  const tabId = (key: TabKey) => `${uid}-tab-${key}`;
  const panelId = `${uid}-panel`;

  const move = (index: number) => {
    const next = TABS[(index + TABS.length) % TABS.length];
    if (next === undefined) return;
    setSelected(next.key);
    buttons.current.get(next.key)?.focus();
  };

  const onKeyDown = (event: KeyboardEvent, index: number) => {
    switch (event.key) {
      case 'ArrowRight':
        move(index + 1);
        break;
      case 'ArrowLeft':
        move(index - 1);
        break;
      case 'Home':
        move(0);
        break;
      case 'End':
        move(TABS.length - 1);
        break;
      default:
        return;
    }
    event.preventDefault();
  };

  return (
    <div className={styles.tabs}>
      <div
        role="tablist"
        aria-label={`${String(turn.turnNo)}턴 자세히`}
        className={styles.tablist}
      >
        {TABS.map((tab, index) => (
          <button
            key={tab.key}
            ref={(node) => {
              if (node === null) buttons.current.delete(tab.key);
              else buttons.current.set(tab.key, node);
            }}
            type="button"
            role="tab"
            id={tabId(tab.key)}
            aria-selected={selected === tab.key}
            aria-controls={panelId}
            tabIndex={selected === tab.key ? 0 : -1}
            className={styles.tab}
            onClick={() => {
              setSelected(tab.key);
            }}
            onKeyDown={(event) => {
              onKeyDown(event, index);
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <div
        role="tabpanel"
        id={panelId}
        tabIndex={0}
        aria-labelledby={tabId(selected)}
        className={styles.panel}
      >
        {selected === 'chart' && (
          <ValueChartPanel season={season} throughTurn={turn.turnNo} />
        )}
        {selected === 'contribution' && (
          <ContributionPanel
            seasonId={season.id}
            turnNo={turn.turnNo}
            currentTurnNo={season.currentTurnNo}
          />
        )}
        {selected === 'table' && (
          <>
            <ValueTablePanel season={season} throughTurn={turn.turnNo} />
            <TurnDetailRows turn={turn} />
          </>
        )}
      </div>
    </div>
  );
}

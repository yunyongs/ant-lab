import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { RankingList } from './RankingList.tsx';
import {
  EMPTY_ASSETS_TEXT,
  EMPTY_GROUPS_TEXT,
  TABS,
  type TabKey,
} from './rankingLabels.ts';
import type { RankingLists } from './rankingRows.ts';
import styles from './Ranking.module.css';

function Panel({ tab, lists }: { tab: TabKey; lists: RankingLists }) {
  switch (tab) {
    case 'change':
      return (
        <>
          <RankingList
            title="오른 종목"
            rows={lists.gainers}
            emptyText={EMPTY_ASSETS_TEXT}
          />
          <RankingList
            title="내린 종목"
            rows={lists.losers}
            emptyText={EMPTY_ASSETS_TEXT}
          />
        </>
      );
    case 'attention':
      return (
        <RankingList
          title="관심이 큰 종목"
          rows={lists.attention}
          emptyText={EMPTY_ASSETS_TEXT}
        />
      );
    case 'streak':
      return (
        <>
          <RankingList
            title="연속으로 오른 종목"
            rows={lists.risingStreaks}
            emptyText={EMPTY_ASSETS_TEXT}
          />
          <RankingList
            title="연속으로 내린 종목"
            rows={lists.fallingStreaks}
            emptyText={EMPTY_ASSETS_TEXT}
          />
        </>
      );
    case 'group':
      return (
        <>
          <RankingList
            title="오른 그룹"
            rows={lists.groupGainers}
            emptyText={EMPTY_GROUPS_TEXT}
          />
          <RankingList
            title="내린 그룹"
            rows={lists.groupLosers}
            emptyText={EMPTY_GROUPS_TEXT}
          />
        </>
      );
  }
}

// WAI-ARIA tabs: arrow keys move and select, Home/End jump, only the
// selected tab is in the tab order, and only its panel is rendered.
export function RankingTabs({ lists }: { lists: RankingLists }) {
  const uid = useId();
  const [selected, setSelected] = useState<TabKey>('change');
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
      <div role="tablist" aria-label="순위 종류" className={styles.tablist}>
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
            aria-controls={selected === tab.key ? panelId : undefined}
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
        aria-labelledby={tabId(selected)}
        className={styles.panel}
      >
        <Panel tab={selected} lists={lists} />
      </div>
    </div>
  );
}

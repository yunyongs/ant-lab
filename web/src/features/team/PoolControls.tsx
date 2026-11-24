import { useEffect, useId, useRef, useState, type RefObject } from 'react';
import styles from './PoolControls.module.css';
import { positionLabel } from '../seasons/labels.ts';
import rowStyles from './PoolRow.module.css';
import {
  ASSET_TYPES,
  MARKETS,
  PARAM,
  POSITION_CHIPS,
  SORTS,
  SORT_KEYS,
  assetTypeLabel,
  chipPressed,
  hasMoreFilters,
  isCustomized,
  marketLabel,
  subFilterPosition,
  type GroupOption,
  type PoolFilters,
} from './poolView.ts';

function nativeComposing(event: { nativeEvent: Event }): boolean {
  return (
    'isComposing' in event.nativeEvent && event.nativeEvent.isComposing === true
  );
}

export function PoolControls({
  filters,
  groups,
  onChange,
  onReset,
  showReset,
  searchRef,
}: {
  filters: PoolFilters;
  groups: GroupOption[];
  onChange: (param: string, value: string) => void;
  onReset: () => void;
  showReset: boolean;
  searchRef: RefObject<HTMLInputElement | null>;
}) {
  const id = useId();
  const [moreOpen, setMoreOpen] = useState(() => hasMoreFilters(filters));
  const subPosition = subFilterPosition(filters.position);
  const fieldId = (name: string) => `${id}-${name}`;
  // The search text is local state: the address updates through an async
  // router transition, and a controlled input fed from it loses keystrokes,
  // moves the caret and breaks Hangul composition.
  const [draft, setDraft] = useState(filters.query);
  const draftRef = useRef(filters.query);
  const written = useRef(new Set<string>());
  const composing = useRef(false);

  const write = (value: string) => {
    written.current.add(value);
    onChange(PARAM.query, value);
  };

  useEffect(() => {
    if (filters.query === draftRef.current) {
      written.current.clear();
    } else if (!written.current.has(filters.query) && !composing.current) {
      // The address changed from outside (clear, back/forward).
      draftRef.current = filters.query;
      written.current.clear();
      setDraft(filters.query);
    }
  }, [filters.query]);

  return (
    <div className={styles.controls} role="search" aria-label="종목 찾기">
      <div className={`${styles.field} ${styles.wide}`}>
        <label htmlFor={fieldId('query')}>종목 검색</label>
        <input
          id={fieldId('query')}
          ref={searchRef}
          type="search"
          className={styles.input}
          placeholder="이름이나 코드"
          autoComplete="off"
          value={draft}
          onChange={(event) => {
            const value = event.target.value;
            draftRef.current = value;
            setDraft(value);
            if (!composing.current && !nativeComposing(event)) write(value);
          }}
          onCompositionStart={() => {
            composing.current = true;
          }}
          onCompositionEnd={(event) => {
            composing.current = false;
            write(event.currentTarget.value);
          }}
        />
      </div>
      <div role="group" aria-label="포지션 거르기" className={rowStyles.chips}>
        {POSITION_CHIPS.map((chip) => (
          <button
            key={chip.label}
            type="button"
            className={rowStyles.chip}
            aria-pressed={chipPressed(chip, filters.position)}
            onClick={() => {
              onChange(PARAM.position, chip.value ?? '');
            }}
          >
            {chip.label}
          </button>
        ))}
        {subPosition !== null && (
          <button
            type="button"
            className={rowStyles.chip}
            aria-pressed="true"
            aria-label={`${positionLabel[subPosition]}만 보는 중, 누르면 해제`}
            onClick={() => {
              onChange(PARAM.position, '');
            }}
          >
            {`${positionLabel[subPosition]} ✕`}
          </button>
        )}
      </div>
      <button
        type="button"
        className={rowStyles.more}
        aria-expanded={moreOpen}
        aria-controls={`${id}-more`}
        onClick={() => {
          setMoreOpen((value) => !value);
        }}
      >
        정렬·필터 더보기
      </button>
      <div id={`${id}-more`} className={styles.more} hidden={!moreOpen}>
        <div className={styles.field}>
          <label htmlFor={fieldId('market')}>시장</label>
          <select
            id={fieldId('market')}
            className={styles.input}
            value={filters.market ?? ''}
            onChange={(event) => {
              onChange(PARAM.market, event.target.value);
            }}
          >
            <option value="">전체</option>
            {MARKETS.map((market) => (
              <option key={market} value={market}>
                {marketLabel[market]}
              </option>
            ))}
          </select>
        </div>
        <div className={styles.field}>
          <label htmlFor={fieldId('type')}>종류</label>
          <select
            id={fieldId('type')}
            className={styles.input}
            value={filters.assetType ?? ''}
            onChange={(event) => {
              onChange(PARAM.assetType, event.target.value);
            }}
          >
            <option value="">전체</option>
            {ASSET_TYPES.map((type) => (
              <option key={type} value={type}>
                {assetTypeLabel[type]}
              </option>
            ))}
          </select>
        </div>
        <div className={styles.field}>
          <label htmlFor={fieldId('group')}>묶음</label>
          <select
            id={fieldId('group')}
            className={styles.input}
            value={filters.groupId ?? ''}
            onChange={(event) => {
              onChange(PARAM.groupId, event.target.value);
            }}
          >
            <option value="">전체</option>
            {groups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </select>
        </div>
        <div className={`${styles.field} ${styles.wide}`}>
          <label htmlFor={fieldId('sort')}>정렬</label>
          <select
            id={fieldId('sort')}
            className={styles.input}
            value={filters.sort ?? ''}
            onChange={(event) => {
              onChange(PARAM.sort, event.target.value);
            }}
          >
            <option value="">기본 순서</option>
            {SORT_KEYS.map((key) => (
              <option key={key} value={key}>
                {SORTS[key].label}
              </option>
            ))}
          </select>
        </div>
        {showReset && isCustomized(filters) && (
          <button
            type="button"
            className={`${styles.reset} ${styles.wide}`}
            onClick={onReset}
          >
            조건 지우기
          </button>
        )}
      </div>
    </div>
  );
}

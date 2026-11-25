import { useState } from 'react';
import type { Season } from '../seasons/labels.ts';
import { AlertListRow } from './AlertListRow.tsx';
import { hasOrderable } from './hasOrderable.ts';
import { itemActions } from './itemActions.ts';
import { fromAlert, type FeedItem } from './orderSource.ts';
import { SourcedOrder } from './SourcedOrder.tsx';
import styles from './AlertList.module.css';

// At most this many banners show at once; the rest sit behind "외 n건".
const VISIBLE = 2;

const noop = () => {};
const keyOf = (item: FeedItem) => `${String(item.turnNo)}-${String(item.seq)}`;

// The nudge alerts of the turn as one-line banners in a plain labelled list:
// not an ARIA alert, no toast, no badge. With none, nothing is rendered.
export function AlertList({
  items,
  feed,
  season,
  onNotice = noop,
}: {
  items: FeedItem[];
  // The turn's feed: when it reports a failed load itself, this list stays quiet.
  feed: FeedItem[];
  season: Season;
  onNotice?: (text: string | null) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  if (items.length === 0) return null;
  const hidden = items.length - VISIBLE;
  const shown = hidden > 0 && !expanded ? items.slice(0, VISIBLE) : items;
  return (
    <section aria-labelledby="alerts-title" className={styles.section}>
      <h3 id="alerts-title" className={styles.title}>
        알림
      </h3>
      {hasOrderable(items, season) ? (
        <SourcedOrder
          season={season}
          onNotice={onNotice}
          showLoadError={!hasOrderable(feed, season)}
        >
          {(order) => (
            <ul className={styles.list}>
              {shown.map((item) => (
                <AlertListRow
                  key={keyOf(item)}
                  item={item}
                  name={
                    item.assetId === null ? null : order.nameOf(item.assetId)
                  }
                  actions={itemActions(item, order.context)}
                  onOrder={(side, opener) => {
                    if (item.assetId === null) return;
                    order.start(side, item.assetId, fromAlert(item), opener);
                  }}
                />
              ))}
            </ul>
          )}
        </SourcedOrder>
      ) : (
        <ul className={styles.list}>
          {shown.map((item) => (
            <AlertListRow
              key={keyOf(item)}
              item={item}
              name={null}
              actions={null}
              onOrder={noop}
            />
          ))}
        </ul>
      )}
      {hidden > 0 && (
        <button
          type="button"
          className={styles.more}
          aria-expanded={expanded}
          onClick={() => {
            setExpanded((open) => !open);
          }}
        >
          {expanded ? '접기' : `외 ${String(hidden)}건`}
        </button>
      )}
    </section>
  );
}

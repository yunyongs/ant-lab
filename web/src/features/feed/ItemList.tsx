import type { Season } from '../seasons/labels.ts';
import { hasOrderable } from './hasOrderable.ts';
import { FeedItemRow } from './FeedItemRow.tsx';
import { itemActions } from './itemActions.ts';
import type { FeedItem, OrderSource } from './orderSource.ts';
import { SourcedOrder } from './SourcedOrder.tsx';
import styles from './Feed.module.css';

const noop = () => {};

// The stored items in the stored order. `sourceOf` decides the source of an
// order started from an item; no order data is requested unless an item can
// start an order.
export function ItemList({
  items,
  season,
  sourceOf,
  onNotice = noop,
  showLoadError = true,
}: {
  items: FeedItem[];
  season: Season;
  sourceOf: (item: FeedItem) => OrderSource;
  onNotice?: (text: string | null) => void;
  showLoadError?: boolean;
}) {
  if (!hasOrderable(items, season)) {
    return (
      <ul className={styles.list}>
        {items.map((item) => (
          <FeedItemRow
            key={`${String(item.turnNo)}-${String(item.seq)}`}
            item={item}
            name={null}
            actions={null}
            onOrder={noop}
          />
        ))}
      </ul>
    );
  }
  return (
    <SourcedOrder
      season={season}
      onNotice={onNotice}
      showLoadError={showLoadError}
    >
      {(order) => (
        <ul className={styles.list}>
          {items.map((item) => (
            <FeedItemRow
              key={`${String(item.turnNo)}-${String(item.seq)}`}
              item={item}
              name={item.assetId === null ? null : order.nameOf(item.assetId)}
              actions={itemActions(item, order.context)}
              onOrder={(side, opener) => {
                if (item.assetId === null) return;
                order.start(side, item.assetId, sourceOf(item), opener);
              }}
            />
          ))}
        </ul>
      )}
    </SourcedOrder>
  );
}

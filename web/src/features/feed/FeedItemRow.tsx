import type { ItemActions } from './itemActions.ts';
import type { FeedItem } from './orderSource.ts';
import styles from './Feed.module.css';

// The sentence is a text child only. Nothing here depends on its content, and
// no identifier of the item is printed or put in an attribute.
export function FeedItemRow({
  item,
  name,
  actions,
  onOrder,
}: {
  item: FeedItem;
  name: string | null;
  actions: ItemActions | null;
  onOrder: (side: 'BUY' | 'SELL', opener: HTMLButtonElement) => void;
}) {
  return (
    <li className={styles.item}>
      <p className={styles.body}>{item.body}</p>
      {actions !== null && name !== null && (
        <div className={styles.buttons}>
          <button
            type="button"
            className={styles.buy}
            aria-label={`${name} 매수`}
            disabled={actions.disabled}
            onClick={(event) => {
              onOrder('BUY', event.currentTarget);
            }}
          >
            매수
          </button>
          {actions.sell && (
            <button
              type="button"
              className={styles.sell}
              aria-label={`${name} 매도`}
              disabled={actions.disabled}
              onClick={(event) => {
                onOrder('SELL', event.currentTarget);
              }}
            >
              매도
            </button>
          )}
        </div>
      )}
    </li>
  );
}

import { messageFor } from '../../api/errorMessages.ts';
import { ProblemError } from '../../api/problem.ts';
import { compareDecimal } from '../format/compareDecimal.ts';
import { formatWon } from '../format/money.ts';
import type { OrderQuote } from './queries.ts';
import styles from './Team.module.css';

const QUOTE_FAILED_TEXT =
  '미리보기를 불러오지 못했습니다. 주문은 그대로 할 수 있습니다.';

export type QuoteView =
  | { kind: 'idle' }
  | { kind: 'pending' }
  | { kind: 'ready'; quote: OrderQuote }
  | { kind: 'failed'; error: unknown };

// Refusals map by code only; the server's detail text is never shown.
function failureText(error: unknown, maxHint: string | null): string | null {
  if (!(error instanceof ProblemError)) return QUOTE_FAILED_TEXT;
  if (error.code === 'UNAUTHENTICATED') return null;
  const text = messageFor(error.code);
  return error.code === 'INSUFFICIENT_CASH' && maxHint !== null
    ? `${text} ${maxHint}`
    : text;
}

function QuoteRows({ quote }: { quote: OrderQuote }) {
  return (
    <dl className={styles.rows}>
      <div className={styles.rowBetween}>
        <dt>예상 체결가</dt>
        <dd>{formatWon(quote.fillPrice)}</dd>
      </div>
      <div className={styles.rowBetween}>
        <dt>예상 대금</dt>
        <dd>{formatWon(quote.notional)}</dd>
      </div>
      <div className={styles.rowBetween}>
        <dt>예상 수수료</dt>
        <dd>{formatWon(quote.fee)}</dd>
      </div>
      {compareDecimal(quote.fxCost, '0') !== 0 && (
        <div className={styles.rowBetween}>
          <dt className={styles.muted}>그중 환전 비용</dt>
          <dd className={styles.muted}>{formatWon(quote.fxCost)}</dd>
        </div>
      )}
      <div className={styles.rowBetween}>
        <dt>체결 뒤 현금</dt>
        <dd>{formatWon(quote.cashAfter)}</dd>
      </div>
    </dl>
  );
}

export function OrderQuotePanel({
  view,
  maxHint,
}: {
  view: QuoteView;
  // Shown after a cash refusal when the largest buyable quantity is known.
  maxHint: string | null;
}) {
  let body;
  if (view.kind === 'idle') {
    body = <p className={styles.hint}>수량을 정하면 미리보기가 나옵니다.</p>;
  } else if (view.kind === 'pending') {
    // Visible only: the region is busy, so the rows are read once they land.
    body = (
      <p className={styles.hint} aria-hidden="true">
        미리보기를 계산하는 중…
      </p>
    );
  } else if (view.kind === 'ready') {
    body = <QuoteRows quote={view.quote} />;
  } else {
    const text = failureText(view.error, maxHint);
    body = text === null ? null : <p className={styles.fieldError}>{text}</p>;
  }
  return (
    <section className={styles.quote} aria-labelledby="order-quote-title">
      <h4 id="order-quote-title" className={styles.cardHeading}>
        주문 미리보기
      </h4>
      <div
        role="status"
        aria-busy={view.kind === 'pending'}
        className={styles.quoteBody}
      >
        {body}
      </div>
      <p className={styles.hint}>
        미리보기일 뿐입니다. 실제 체결은 주문 결과를 따릅니다.
      </p>
    </section>
  );
}

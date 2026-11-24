import {
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type FormEvent,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { messageFor } from '../../api/errorMessages.ts';
import { Change } from '../format/Change.tsx';
import { ProblemError } from '../../api/problem.ts';
import { formatWon } from '../format/money.ts';
import { UNKNOWN_OUTCOME_TEXT } from '../turn/turnMessages.ts';
import type { OrderSource } from '../feed/orderSource.ts';
import {
  addDecimal,
  quantityShare,
  subtractDecimal,
  weightAfterOrder,
} from '../format/derive.ts';
import { groupDigits, parseWholeNumber } from '../format/integer.ts';
import { POSITIONS, positionLabel, type Position } from '../seasons/labels.ts';
import { OrderQuotePanel, type QuoteView } from './OrderQuotePanel.tsx';
import {
  QUOTE_DELAY_MS,
  holdingsKey,
  useOrderQuote,
  usePlaceOrder,
  type Holding,
  type Holdings,
  type OrderRequest,
  type OrderResult,
  type PoolAsset,
} from './queries.ts';
import { useDebounced } from './useDebounced.ts';
import styles from './Team.module.css';
import sheetStyles from './OrderSheet.module.css';

const MAX_QUANTITY = 999_999_999;
const RATIOS = [10, 25, 50] as const;
const QUANTITY_TEXT = '수량은 1 이상의 정수로 적어 주세요';
const PRICES_FAILED_TEXT =
  '가격을 불러오지 못했습니다. 체결가는 서버가 정합니다.';
const FIELD_TEXT = {
  quantity: QUANTITY_TEXT,
  declaredPosition: '선언 포지션을 확인해 주세요.',
} as const;

interface Failure {
  alert: string | null;
  fields: Partial<Record<keyof typeof FIELD_TEXT, string>>;
}

function failureOf(error: unknown): Failure {
  if (!(error instanceof ProblemError)) {
    // No readable answer: the hook has already asked the server again.
    return { alert: UNKNOWN_OUTCOME_TEXT, fields: {} };
  }
  if (error.code === 'UNAUTHENTICATED') return { alert: null, fields: {} };
  if (error.status === 400 && error.code === 'INVALID_ARGUMENT') {
    const fields: Failure['fields'] = {};
    let unmapped = error.errors.length === 0;
    for (const violation of error.errors) {
      if (violation.field === 'quantity') fields.quantity = FIELD_TEXT.quantity;
      else if (violation.field === 'declaredPosition') {
        fields.declaredPosition = FIELD_TEXT.declaredPosition;
      } else unmapped = true;
    }
    return { alert: unmapped ? messageFor('INVALID_ARGUMENT') : null, fields };
  }
  return { alert: messageFor(error.code), fields: {} };
}

function isCashRefusal(error: unknown): boolean {
  return error instanceof ProblemError && error.code === 'INSUFFICIENT_CASH';
}

function ResultRows({ result }: { result: OrderResult }) {
  return (
    <>
      <dl className={styles.rows}>
        <div className={styles.rowBetween}>
          <dt>체결가</dt>
          <dd>{formatWon(result.fillPrice)}</dd>
        </div>
        <div className={styles.rowBetween}>
          <dt>대금</dt>
          <dd>{formatWon(result.notional)}</dd>
        </div>
        <div className={styles.rowBetween}>
          <dt>수수료</dt>
          <dd>{formatWon(result.fee)}</dd>
        </div>
        <div className={styles.rowBetween}>
          <dt>현금 잔액</dt>
          <dd>{formatWon(result.cashBalance)}</dd>
        </div>
        <div className={styles.rowBetween}>
          <dt>평균단가</dt>
          <dd>{formatWon(result.avgPrice)}</dd>
        </div>
        {result.realizedPnl !== null && (
          <div className={styles.rowBetween}>
            <dt>실현 손익</dt>
            <dd>
              <Change value={result.realizedPnl} kind="won" />
            </dd>
          </div>
        )}
        <div className={styles.rowBetween}>
          <dt>보유</dt>
          <dd>
            {result.side === 'SELL' && result.holdingQuantity === 0
              ? '보유 0주 · 이 종목의 구간이 닫혔습니다'
              : `${String(result.holdingQuantity)}주`}
          </dd>
        </div>
        <div className={styles.rowBetween}>
          <dt>선언</dt>
          <dd>{positionLabel[result.declaredPosition]}</dd>
        </div>
      </dl>
      {result.formationChangeId !== null && (
        <p className={styles.noMargin}>
          {`이번 턴 전술 변경에 묶인 주문입니다 (변경 번호 ${String(result.formationChangeId)})`}
        </p>
      )}
    </>
  );
}

export function OrderSheet({
  side,
  asset,
  holding,
  price,
  pricesFailed,
  seasonId,
  turnNo,
  cashBalance,
  source,
  onClose,
  onStale,
}: {
  side: 'BUY' | 'SELL';
  // A held asset opens a sell sheet by name only; a new buy comes from the pool.
  asset: Pick<PoolAsset, 'assetId' | 'name'> & {
    systemPosition?: PoolAsset['systemPosition'];
  };
  holding: Holding | undefined;
  price: string | undefined;
  pricesFailed: boolean;
  seasonId: number;
  turnNo: number;
  cashBalance: string;
  // Where the order started; left out for an order from the trade screen.
  source?: OrderSource;
  onClose: () => void;
  onStale: (code: 'TURN_MISMATCH' | 'SEASON_NOT_ACTIVE') => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const alertBox = useRef<HTMLDivElement>(null);
  const queryClient = useQueryClient();
  const order = usePlaceOrder(seasonId);
  // The system's judgement is preselected; the player may write otherwise.
  const [position, setPosition] = useState<Position>(
    asset.systemPosition ?? 'MIDFIELDER',
  );
  const [quantityText, setQuantityText] = useState('1');
  const [result, setResult] = useState<OrderResult | null>(null);
  const [error, setError] = useState<unknown>(null);
  // Holdings update count when the last fill came back; null before any fill.
  const [filledAt, setFilledAt] = useState<number | null>(null);

  // No cleanup: closing here would fire `close` during a development
  // StrictMode re-run and dismiss the sheet. Unmounting removes a modal.
  useEffect(() => {
    const element = dialog.current;
    if (element !== null && !element.open) element.showModal();
  }, []);

  const selling = side === 'SELL';
  // The held quantity is a server integer; the server judges a stale one.
  const limit = selling ? (holding?.quantity ?? 0) : MAX_QUANTITY;
  const quantityHint = selling
    ? `보유 수량(${groupDigits(limit)}주) 이하의 정수로 적어 주세요`
    : QUANTITY_TEXT;
  const quantity = parseWholeNumber(quantityText, limit);
  const quantityOk = quantity !== null && quantity >= 1;
  // The quote is asked only while the form is on screen.
  const quoting = result === null;
  const quoteQuantity = quoting && quantityOk ? quantity : null;
  const failure = error === null ? null : failureOf(error);
  const alertText = failure?.alert ?? null;

  function bodyFor(count: number, declared: Position = position): OrderRequest {
    return {
      expectedTurnNo: turnNo,
      side,
      assetId: asset.assetId,
      quantity: count,
      // An asset already held keeps its declared position: it is not sent.
      ...(!selling && holding === undefined
        ? { declaredPosition: declared }
        : {}),
      ...(source === undefined ? {} : { source }),
    };
  }

  // The quote follows the input after a pause; a quote for any other input
  // than the one on screen is never shown.
  const settledQuantity = useDebounced(quoteQuantity, QUOTE_DELAY_MS);
  const settledPosition = useDebounced(position, QUOTE_DELAY_MS);
  const settled =
    settledQuantity === quoteQuantity && settledPosition === position;
  // The cash on screen is part of the quote key: when it moves (a fill,
  // a refresh), the quote and the max are asked again.
  const quote = useOrderQuote(
    seasonId,
    quoting && settledQuantity !== null
      ? bodyFor(settledQuantity, settledPosition)
      : null,
    cashBalance,
  );
  const refusedForCash = isCashRefusal(quote.error);

  // A quote above the max is refused without the max, so the last max seen
  // is kept for the turn and cash it was learned at; without one, a
  // one-share quote asks for it.
  const maxBasis = `${String(turnNo)}|${cashBalance}`;
  const [learnedMax, setLearnedMax] = useState<{
    max: number;
    basis: string;
  } | null>(null);
  const knownMax = learnedMax?.basis === maxBasis ? learnedMax.max : null;
  const probe = useOrderQuote(
    seasonId,
    quoting &&
      !selling &&
      knownMax === null &&
      refusedForCash &&
      settledQuantity !== 1
      ? bodyFor(1, settledPosition)
      : null,
    cashBalance,
  );
  const seenMax =
    quote.data?.maxBuyQuantity ??
    probe.data?.maxBuyQuantity ??
    ((refusedForCash && settledQuantity === 1) || isCashRefusal(probe.error)
      ? 0
      : null);
  if (!selling && seenMax !== null && seenMax !== knownMax) {
    setLearnedMax({ max: seenMax, basis: maxBasis });
  }
  const buyMax = knownMax === null ? null : Math.min(knownMax, MAX_QUANTITY);

  let quoteView: QuoteView;
  if (quoteQuantity === null) quoteView = { kind: 'idle' };
  else if (!settled || quote.isPending) quoteView = { kind: 'pending' };
  else if (quote.isError) quoteView = { kind: 'failed', error: quote.error };
  else quoteView = { kind: 'ready', quote: quote.data };

  // The base of the ratio buttons: the server's maximum, or the held shares.
  const ratioBase = selling ? limit : buyMax;
  function pickRatio(percent: number) {
    if (ratioBase === null) return;
    const share = quantityShare(String(ratioBase), percent);
    if (share === null) return;
    // A share below one still names one whole share.
    setQuantityText(share === '0' ? '1' : share);
  }

  // Previews derived from the quote and the holdings, exact; hidden when any
  // input is missing or malformed.
  // Read from the cache only: the sheet adds no holdings request of its own.
  const holdings = queryClient.getQueryData<Holdings>(holdingsKey(seasonId));
  const holdingsState = useSyncExternalStore(
    (notify) => queryClient.getQueryCache().subscribe(notify),
    () => queryClient.getQueryState(holdingsKey(seasonId)),
  );
  // After a fill in this sheet the holdings and the cash on screen are the
  // state before it until the refresh that follows has landed. Anything
  // derived from them is hidden until then; the quote alone is still shown.
  const stateCurrent =
    filledAt === null ||
    (holdingsState !== undefined &&
      holdingsState.fetchStatus === 'idle' &&
      holdingsState.dataUpdateCount > filledAt);
  const readyQuote = quoteView.kind === 'ready' ? quoteView.quote : null;
  const totalValue = holdings?.totalValue;
  let weightAfter: string | null = null;
  let total: string | null = null;
  if (readyQuote !== null) {
    if (!selling) total = addDecimal(readyQuote.notional, readyQuote.fee);
    else if (stateCurrent) {
      total = subtractDecimal(readyQuote.cashAfter, cashBalance);
    }
    if (stateCurrent && totalValue !== undefined) {
      weightAfter = weightAfterOrder(
        holding?.value ?? '0',
        readyQuote.notional,
        totalValue,
        readyQuote.fee,
        side,
      );
    }
  }

  useEffect(() => {
    if (alertText !== null) alertBox.current?.focus();
  }, [alertText, error]);

  // Closing through the dialog lets the browser release the modal before
  // `onClose` moves focus back to the card.
  function dismiss() {
    const element = dialog.current;
    if (element?.open) element.close();
    else onClose();
  }

  function step(delta: number) {
    const current = quantity ?? 0;
    const next = Math.max(1, Math.min(limit, current + delta));
    setQuantityText(String(next));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!quantityOk || order.isPending) return;
    const body = bodyFor(quantity);
    setError(null);
    order.mutate(body, {
      onSuccess: (filled) => {
        setFilledAt(
          queryClient.getQueryState(holdingsKey(seasonId))?.dataUpdateCount ??
            0,
        );
        setResult(filled);
      },
      onError: (caught) => {
        if (
          caught instanceof ProblemError &&
          (caught.code === 'TURN_MISMATCH' ||
            caught.code === 'SEASON_NOT_ACTIVE')
        ) {
          onStale(caught.code);
          return;
        }
        setError(caught);
      },
    });
  }

  return (
    <dialog
      ref={dialog}
      className={styles.sheet}
      aria-labelledby="order-sheet-title"
      onClose={onClose}
      onCancel={(event) => {
        // A pending order cannot be cancelled by closing the sheet.
        if (order.isPending) event.preventDefault();
      }}
    >
      <div className={styles.sheetBody}>
        <h3 id="order-sheet-title" className={styles.cardHeading}>
          {selling ? `${asset.name} 매도` : asset.name}
        </h3>
        {result === null ? (
          <form className={styles.form} onSubmit={submit} noValidate>
            {!selling && asset.systemPosition !== undefined && (
              <p className={styles.noMargin}>
                {`시스템 판정: ${positionLabel[asset.systemPosition]}`}
              </p>
            )}
            <p className={sheetStyles.subline}>
              <span>이번 턴 가격</span>{' '}
              <span>{price === undefined ? '–' : formatWon(price)}</span>
              {' · '}
              <span>{`현금 ${formatWon(cashBalance)}`}</span>
            </p>
            {pricesFailed && price === undefined && (
              <p className={styles.hint}>{PRICES_FAILED_TEXT}</p>
            )}
            {selling && (
              <div className={styles.rowBetween}>
                <span className={styles.muted}>보유</span>
                <span>{`보유 ${groupDigits(limit)}주`}</span>
              </div>
            )}

            {alertText !== null && (
              <div
                ref={alertBox}
                role="alert"
                tabIndex={-1}
                className={styles.alert}
              >
                <p className={styles.noMargin}>{alertText}</p>
              </div>
            )}

            {selling ? null : holding === undefined ? (
              <label className={styles.field}>
                <span>선언 포지션</span>
                <select
                  className={styles.select}
                  value={position}
                  aria-invalid={
                    failure?.fields.declaredPosition ? true : undefined
                  }
                  aria-describedby={
                    failure?.fields.declaredPosition
                      ? 'order-position-error'
                      : undefined
                  }
                  onChange={(e) => {
                    setPosition(e.target.value as Position);
                  }}
                >
                  {POSITIONS.map((value) => (
                    <option key={value} value={value}>
                      {value === asset.systemPosition
                        ? `${positionLabel[value]} (시스템 판정과 같음)`
                        : positionLabel[value]}
                    </option>
                  ))}
                </select>
                {failure?.fields.declaredPosition && (
                  <span id="order-position-error" className={styles.fieldError}>
                    {failure.fields.declaredPosition}
                  </span>
                )}
                <span className={styles.hint}>
                  처음 살 때는 꼭 정합니다. 시스템 판정과 달라도 되지만 성적표의
                  &quot;역할 수행&quot;에 반영됩니다.
                </span>
              </label>
            ) : (
              <p className={styles.noMargin}>
                {`선언 포지션: ${positionLabel[holding.declaredPosition]} (보유 중이라 바꿀 수 없습니다)`}
              </p>
            )}

            <div className={styles.field}>
              <label htmlFor="order-quantity">수량</label>
              <div className={styles.stepper}>
                <button
                  type="button"
                  className={styles.step}
                  aria-label="수량 줄이기"
                  onClick={() => {
                    step(-1);
                  }}
                >
                  −
                </button>
                <input
                  id="order-quantity"
                  type="text"
                  inputMode="numeric"
                  autoComplete="off"
                  className={styles.numberInput}
                  value={quantityText}
                  aria-invalid={
                    !quantityOk || failure?.fields.quantity ? true : undefined
                  }
                  aria-describedby={
                    !quantityOk || failure?.fields.quantity
                      ? 'order-quantity-error'
                      : undefined
                  }
                  onChange={(e) => {
                    setQuantityText(e.target.value);
                  }}
                />
                <button
                  type="button"
                  className={styles.step}
                  aria-label="수량 늘리기"
                  onClick={() => {
                    step(1);
                  }}
                >
                  +
                </button>
              </div>
              <div
                role="group"
                aria-label="최대 수량 대비"
                className={sheetStyles.ratios}
              >
                {RATIOS.map((percent) => (
                  <button
                    key={percent}
                    type="button"
                    className={`${styles.secondary} ${sheetStyles.ratio}`}
                    disabled={ratioBase === null || ratioBase === 0}
                    onClick={() => {
                      pickRatio(percent);
                    }}
                  >
                    {`${String(percent)}%`}
                  </button>
                ))}
                <button
                  type="button"
                  className={`${styles.secondary} ${sheetStyles.ratio}`}
                  disabled={ratioBase === null || ratioBase === 0}
                  onClick={() => {
                    pickRatio(100);
                  }}
                >
                  {selling ? '전량 ' : '최대 '}
                  {ratioBase !== null && (
                    <span className={sheetStyles.ratioCount}>
                      {`(${groupDigits(ratioBase)}주)`}
                    </span>
                  )}
                </button>
              </div>
              {!quantityOk && (
                <span id="order-quantity-error" className={styles.fieldError}>
                  {quantityHint}
                </span>
              )}
              {quantityOk && failure?.fields.quantity && (
                <span id="order-quantity-error" className={styles.fieldError}>
                  {failure.fields.quantity}
                </span>
              )}
            </div>

            <OrderQuotePanel
              view={quoteView}
              weightAfter={weightAfter}
              maxHint={
                buyMax === null || selling
                  ? null
                  : buyMax === 0
                    ? '지금 현금으로는 1주도 살 수 없습니다.'
                    : `지금은 최대 ${groupDigits(buyMax)}주까지 살 수 있습니다.`
              }
            />

            <div className={styles.actions}>
              <button
                type="button"
                className={styles.secondary}
                disabled={order.isPending}
                onClick={dismiss}
              >
                취소
              </button>
              <button
                type="submit"
                className={`${selling ? styles.danger : styles.primary} ${sheetStyles.submit}`}
                disabled={!quantityOk || order.isPending}
              >
                {order.isPending ? (
                  `${selling ? '매도' : '매수'} 중…`
                ) : (
                  <>
                    <span>{`${quantityOk ? String(quantity) : '–'}주 ${selling ? '매도' : '매수'}`}</span>
                    {total !== null && (
                      <span
                        className={sheetStyles.submitTotal}
                        aria-hidden="true"
                      >
                        {`${selling ? '받는 금액' : '합계'} ${formatWon(total)}`}
                      </span>
                    )}
                  </>
                )}
              </button>
            </div>
          </form>
        ) : (
          <div className={styles.form}>
            <ResultRows result={result} />
            <div className={styles.actions}>
              <button
                type="button"
                className={styles.secondary}
                onClick={dismiss}
              >
                닫기
              </button>
              {(!selling || result.holdingQuantity > 0) && (
                <button
                  type="button"
                  className={styles.primary}
                  onClick={() => {
                    setResult(null);
                    setQuantityText('1');
                    // Cash moved with the fill: the next quote gives the max.
                    setLearnedMax(null);
                    order.reset();
                  }}
                >
                  {selling ? '이 종목 더 팔기' : '이 종목 더 사기'}
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </dialog>
  );
}

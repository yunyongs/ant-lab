import { useEffect, useRef, useState, type FormEvent } from 'react';
import { messageFor } from '../../api/errorMessages.ts';
import { ProblemError } from '../../api/problem.ts';
import { formatWon } from '../format/money.ts';
import { UNKNOWN_OUTCOME_TEXT } from '../turn/turnMessages.ts';
import { parseWholeNumber } from '../format/integer.ts';
import { POSITIONS, positionLabel, type Position } from '../seasons/labels.ts';
import {
  usePlaceOrder,
  type Holding,
  type OrderRequest,
  type OrderResult,
  type PoolAsset,
} from './queries.ts';
import styles from './Team.module.css';

const MAX_QUANTITY = 999_999_999;
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
            <dd>{formatWon(result.realizedPnl)}</dd>
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
  onClose: () => void;
  onStale: (code: 'TURN_MISMATCH' | 'SEASON_NOT_ACTIVE') => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const alertBox = useRef<HTMLDivElement>(null);
  const order = usePlaceOrder(seasonId);
  // The system's judgement is preselected; the player may write otherwise.
  const [position, setPosition] = useState<Position>(
    asset.systemPosition ?? 'MIDFIELDER',
  );
  const [quantityText, setQuantityText] = useState('1');
  const [result, setResult] = useState<OrderResult | null>(null);
  const [error, setError] = useState<unknown>(null);

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
    ? `보유 수량(${String(limit)}주) 이하의 정수로 적어 주세요`
    : QUANTITY_TEXT;
  const quantity = parseWholeNumber(quantityText, limit);
  const quantityOk = quantity !== null && quantity >= 1;
  const failure = error === null ? null : failureOf(error);
  const alertText = failure?.alert ?? null;

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
    const body: OrderRequest = {
      expectedTurnNo: turnNo,
      side,
      assetId: asset.assetId,
      quantity,
      // An asset already held keeps its declared position: it is not sent.
      ...(!selling && holding === undefined
        ? { declaredPosition: position }
        : {}),
    };
    setError(null);
    order.mutate(body, {
      onSuccess: (filled) => {
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
            <div className={styles.rowBetween}>
              <span className={styles.muted}>이번 턴 가격</span>
              <span>{price === undefined ? '–' : formatWon(price)}</span>
            </div>
            {pricesFailed && price === undefined && (
              <p className={styles.hint}>{PRICES_FAILED_TEXT}</p>
            )}
            {selling && (
              <div className={styles.rowBetween}>
                <span className={styles.muted}>보유</span>
                <span>{`보유 ${String(limit)}주`}</span>
              </div>
            )}
            <div className={styles.rowBetween}>
              <span className={styles.muted}>현금 잔액</span>
              <span>{formatWon(cashBalance)}</span>
            </div>

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
              {selling && (
                <button
                  type="button"
                  className={styles.secondary}
                  onClick={() => {
                    setQuantityText(String(limit));
                  }}
                >
                  {`전량 (${String(limit)}주)`}
                </button>
              )}
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
                className={selling ? styles.danger : styles.primary}
                disabled={!quantityOk || order.isPending}
              >
                {order.isPending
                  ? `${selling ? '매도' : '매수'} 중…`
                  : `${quantityOk ? String(quantity) : '–'}주 ${selling ? '매도' : '매수'}`}
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

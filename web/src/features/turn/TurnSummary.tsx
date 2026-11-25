import { useQueryClient } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';
import type { Season } from '../seasons/labels.ts';
import { seasonKey } from '../seasons/queries.ts';
import { useTurnContribution } from '../contribution/queries.ts';
import { Change } from '../format/Change.tsx';
import { shareWidth } from '../format/derive.ts';
import { formatWon } from '../format/money.ts';
import { ratioToPoints, ratioWithPercent } from '../format/ratio.ts';
import { signedChange } from '../format/signedChange.ts';
import type { TurnResult } from './queries.ts';
import styles from './TurnSummary.module.css';

const withoutMinus = (ratio: string) => ratio.replace(/^-/, '');

// The market baseline row has its own loading and error line: the rest of the
// summary never waits for it. The query is the one the contribution list uses.
function BaselineRow({ turn }: { turn: TurnResult }) {
  const query = useTurnContribution(turn.seasonId, turn.turnNo, true);
  if (query.isPending) {
    return <p className={styles.note}>시장 기준선을 불러오는 중입니다…</p>;
  }
  if (query.isError) {
    return (
      <p className={styles.note}>
        시장 기준선을 불러오지 못했습니다.{' '}
        <button
          type="button"
          className={styles.retry}
          onClick={() => {
            void query.refetch();
          }}
        >
          다시 불러오기
        </button>
      </p>
    );
  }
  const { benchmark } = query.data;
  const share = signedChange(benchmark.totalReturn, 'percent');
  return (
    <div className={styles.baseline}>
      <div className={styles.baselineText}>
        <span className={styles.label}>시장 기준선보다</span>
        <span className={styles.sub}>
          {`기준선 ${formatWon(benchmark.value)} (${share.text})`}
        </span>
      </div>
      <span className={styles.excess}>
        <Change value={benchmark.excessReturn} kind="percent" />
      </span>
    </div>
  );
}

function DrawdownMeter({ turn }: { turn: TurnResult }) {
  const width = shareWidth(
    withoutMinus(turn.drawdown),
    withoutMinus(turn.riskLimit),
  );
  const drawdown = ratioWithPercent(turn.drawdown);
  const limit = ratioWithPercent(turn.riskLimit);
  const breached = turn.riskBreached;
  return (
    <div className={styles.drawdown}>
      <div className={styles.drawdownLine}>
        <span className={styles.label}>누적 낙폭</span>
        <span>{`${drawdown} / 위험 선언 ${limit}`}</span>
      </div>
      {width !== null && (
        <div
          role="meter"
          aria-label="위험 선언 대비 누적 낙폭"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={parseInt(width, 10)}
          aria-valuetext={`위험 선언 ${limit} 중 낙폭 ${drawdown}`}
          className={styles.meter}
        >
          <div
            className={breached ? styles.meterFillOver : styles.meterFill}
            style={{ width }}
          />
        </div>
      )}
      <span className={breached ? styles.over : styles.ok}>
        {breached
          ? `초과 — ${ratioToPoints(withoutMinus(turn.riskHeadroom))} 더 내려갔습니다`
          : `선언 안 — 여유 ${ratioToPoints(turn.riskHeadroom)}`}
      </span>
    </div>
  );
}

// The conclusion of the turn: value, this turn and season returns, the market
// baseline, and the drawdown against the declared limit. Server figures only.
// The season return comes from the season itself and only while this turn is
// the live one: on a past turn the latest figure would be wrong, so it is left
// out. It never waits for the baseline (contribution) query.
export function TurnSummary({ turn }: { turn: TurnResult }) {
  // A pure read of the season the page already loaded: no observer, no fetch.
  const client = useQueryClient();
  const season = useSyncExternalStore(
    (notify) => client.getQueryCache().subscribe(notify),
    () => client.getQueryData<Season>(seasonKey(turn.seasonId)),
  );
  const seasonReturn =
    season !== undefined && season.currentTurnNo === turn.turnNo
      ? season.totalReturn
      : null;
  return (
    <section
      className={styles.summary}
      aria-label={`${String(turn.turnNo)}턴 요약`}
      data-breached={turn.riskBreached ? 'true' : 'false'}
    >
      <div className={styles.head}>
        <span className={styles.label}>총 평가액</span>
        <strong className={styles.total}>{formatWon(turn.totalValue)}</strong>
        <span className={styles.returns}>
          <span>이번 턴 </span>
          <Change value={turn.periodReturn} kind="percent" />
          {seasonReturn !== null && (
            <>
              <span> · 시즌 </span>
              <Change value={seasonReturn} kind="percent" />
            </>
          )}
        </span>
      </div>
      <BaselineRow turn={turn} />
      <DrawdownMeter turn={turn} />
    </section>
  );
}

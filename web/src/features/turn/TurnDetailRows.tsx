import { Change } from '../format/Change.tsx';
import { formatWon } from '../format/money.ts';
import { ratioWithPoints } from '../format/ratio.ts';
import { WeightBars } from '../seasons/WeightBars.tsx';
import { weightRowsOf } from '../seasons/weightRows.ts';
import type { TurnResult } from './queries.ts';
import {
  BREACH_BODY,
  BREACH_BODY_FINISHED,
  BREACH_TITLE,
  FORMATION_TURN_NOTE,
} from './turnMessages.ts';
import styles from './Turn.module.css';

// Shown above the tabs when the declared limit was crossed.
export function BreachNotice({ turn }: { turn: TurnResult }) {
  if (!turn.riskBreached) return null;
  return (
    <div className={styles.breach}>
      <p className={styles.strong}>{BREACH_TITLE}</p>
      <p>{turn.status === 'FINISHED' ? BREACH_BODY_FINISHED : BREACH_BODY}</p>
    </div>
  );
}

// The rows of the turn result that the summary does not show: cash, holdings
// value, position weights and the formation deviation. Server figures only.
export function TurnDetailRows({ turn }: { turn: TurnResult }) {
  return (
    <>
      <section className={styles.card} aria-label="현금과 보유">
        <dl className={styles.rows}>
          <div className={styles.row}>
            <dt>직전 턴 대비</dt>
            <dd>
              <Change value={turn.valueChange} kind="won" />
            </dd>
          </div>
          <div className={styles.row}>
            <dt>현금</dt>
            <dd>{formatWon(turn.cashBalance)}</dd>
          </div>
          <div className={styles.row}>
            <dt>보유 평가액</dt>
            <dd>{formatWon(turn.holdingsValue)}</dd>
          </div>
        </dl>
      </section>
      <section className={styles.card} aria-label="포지션 비중">
        <h3 className={styles.cardHeading}>포지션 비중 (검은 선 = 목표)</h3>
        <WeightBars rows={weightRowsOf(turn, turn.targetFormation)} />
        <dl className={styles.rows}>
          <div className={styles.row}>
            <dt>포메이션 이탈</dt>
            <dd>{ratioWithPoints(turn.formationDeviation)}</dd>
          </div>
        </dl>
        <p className={styles.hint}>{FORMATION_TURN_NOTE}</p>
      </section>
      <p className={styles.hint}>
        이 턴의 결과는 턴을 넘긴 시점의 값입니다. 이번 턴에 사고판 결과는 매매
        화면의 평가액에 바로 반영됩니다.
      </p>
    </>
  );
}

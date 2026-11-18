import { useEffect, useRef, useState, type FormEvent } from 'react';
import { messageFor } from '../../api/errorMessages.ts';
import { ProblemError } from '../../api/problem.ts';
import { POSITIONS, positionLabel, type Position } from '../seasons/labels.ts';
import type { Holding } from '../team/queries.ts';
import teamStyles from '../team/Team.module.css';
import { UNKNOWN_OUTCOME_TEXT } from '../turn/turnMessages.ts';
import { useChangeLineup, type LineupChange } from './queries.ts';
import {
  POSITION_ALREADY_TEXT,
  POSITION_NOT_HELD_TEXT,
  POSITION_RECORD_TEXT,
} from './tradeMessages.ts';
import styles from './Trade.module.css';

function alertOf(error: unknown): string | null {
  if (!(error instanceof ProblemError)) return UNKNOWN_OUTCOME_TEXT;
  if (error.code === 'UNAUTHENTICATED') return null;
  if (error.code === 'INSUFFICIENT_HOLDING') return POSITION_NOT_HELD_TEXT;
  if (error.code === 'LINEUP_MISMATCH') return POSITION_RECORD_TEXT;
  return messageFor(error.code);
}

// Changes only the declared position of a held asset: no order, no fee.
// The asset id is sent in the body and never printed.
export function PositionSheet({
  holding,
  name,
  systemPosition,
  seasonId,
  turnNo,
  onClose,
  onStale,
}: {
  holding: Holding | undefined;
  name: string;
  systemPosition: Position | undefined;
  seasonId: number;
  turnNo: number;
  onClose: () => void;
  onStale: (code: 'TURN_MISMATCH' | 'SEASON_NOT_ACTIVE') => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const alertBox = useRef<HTMLDivElement>(null);
  const change = useChangeLineup(seasonId);
  const current = holding?.declaredPosition;
  const [picked, setPicked] = useState<Position>(
    current ?? systemPosition ?? 'MIDFIELDER',
  );
  const [result, setResult] = useState<LineupChange | null>(null);
  const [error, setError] = useState<unknown>(null);

  // No cleanup: closing here would fire `close` during a development
  // StrictMode re-run and dismiss the sheet. Unmounting removes a modal.
  useEffect(() => {
    const element = dialog.current;
    if (element !== null && !element.open) element.showModal();
  }, []);

  const alertText = error === null ? null : alertOf(error);
  useEffect(() => {
    if (alertText !== null) alertBox.current?.focus();
  }, [alertText, error]);

  function dismiss() {
    const element = dialog.current;
    if (element?.open) element.close();
    else onClose();
  }

  const unchanged = holding === undefined || picked === current;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (holding === undefined || unchanged || change.isPending) return;
    setError(null);
    change.mutate(
      {
        expectedTurnNo: turnNo,
        assetId: holding.assetId,
        declaredPosition: picked,
      },
      {
        onSuccess: (answer) => {
          setResult(answer);
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
      },
    );
  }

  return (
    <dialog
      ref={dialog}
      className={teamStyles.sheet}
      aria-labelledby="position-sheet-title"
      onClose={onClose}
      onCancel={(event) => {
        if (change.isPending) event.preventDefault();
      }}
    >
      <div className={teamStyles.sheetBody}>
        <h3 id="position-sheet-title" className={teamStyles.cardHeading}>
          {`${name} 포지션 변경`}
        </h3>
        {result === null ? (
          <form className={teamStyles.form} onSubmit={submit} noValidate>
            {systemPosition !== undefined && (
              <p className={teamStyles.noMargin}>
                {`시스템 판정: ${positionLabel[systemPosition]}`}
              </p>
            )}
            {alertText !== null && (
              <div
                ref={alertBox}
                role="alert"
                tabIndex={-1}
                className={teamStyles.alert}
              >
                <p className={teamStyles.noMargin}>{alertText}</p>
              </div>
            )}
            <fieldset className={styles.radioGroup}>
              <legend className={styles.radioLegend}>선언 포지션</legend>
              {POSITIONS.map((value) => (
                <label key={value} className={styles.radio}>
                  <input
                    type="radio"
                    name="declared-position"
                    value={value}
                    checked={picked === value}
                    onChange={() => {
                      setPicked(value);
                    }}
                  />
                  <span>
                    {value === current
                      ? `${positionLabel[value]} (지금)`
                      : positionLabel[value]}
                  </span>
                </label>
              ))}
            </fieldset>
            <p className={teamStyles.hint}>
              거래 없이 선언만 바꿉니다. 다음 턴부터 새 포지션으로 평가합니다.
            </p>
            <div className={teamStyles.actions}>
              <button
                type="button"
                className={teamStyles.secondary}
                disabled={change.isPending}
                onClick={dismiss}
              >
                닫기
              </button>
              <button
                type="submit"
                className={teamStyles.primary}
                disabled={unchanged || change.isPending}
              >
                {change.isPending ? '바꾸는 중…' : '바꾸기'}
              </button>
            </div>
          </form>
        ) : (
          <div className={teamStyles.form}>
            <p role="status" className={teamStyles.noMargin}>
              {result.changed
                ? `선언 포지션: ${positionLabel[result.declaredPosition]}. 다음 턴부터 새 포지션으로 평가합니다.`
                : POSITION_ALREADY_TEXT}
            </p>
            <div className={teamStyles.actions}>
              <button
                type="button"
                className={teamStyles.primary}
                onClick={dismiss}
              >
                닫기
              </button>
            </div>
          </div>
        )}
      </div>
    </dialog>
  );
}

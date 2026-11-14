import { useEffect, useRef } from 'react';
import { messageFor } from '../../api/errorMessages.ts';
import { ProblemError } from '../../api/problem.ts';
import type { Season } from '../seasons/labels.ts';
import { advanceLabel } from './advanceLabel.ts';
import { useAdvanceTurn } from './queries.ts';
import { ADVANCING_TEXT } from './turnMessages.ts';
import styles from './Turn.module.css';

// Refusals that are shown as a page notice instead (the screen is stale).
function isStaleCode(error: ProblemError): boolean {
  return error.code === 'TURN_MISMATCH' || error.code === 'SEASON_NOT_ACTIVE';
}

export function AdvanceTurnButton({
  season,
  onNotice,
  onAdvanced,
}: {
  season: Pick<Season, 'id' | 'currentTurnNo' | 'pathLength'>;
  onNotice: (text: string | null) => void;
  onAdvanced: (turnNo: number) => void;
}) {
  const advance = useAdvanceTurn(season.id, { onAdvanced, onNotice });
  // Two taps before the first re-render must send one request.
  const inFlight = useRef(false);
  const alertBox = useRef<HTMLDivElement>(null);

  const error: unknown = advance.error;
  const problem =
    error instanceof ProblemError &&
    error.code !== 'UNAUTHENTICATED' &&
    !isStaleCode(error)
      ? error
      : null;
  const alertText = problem === null ? null : messageFor(problem.code);

  useEffect(() => {
    if (alertText !== null) alertBox.current?.focus();
  }, [alertText, advance.error]);

  function submit() {
    if (inFlight.current || advance.isPending) return;
    inFlight.current = true;
    onNotice(null);
    // The turn the screen shows: the server rejects it if it is out of date.
    advance.mutate(season.currentTurnNo, {
      onSettled: () => {
        inFlight.current = false;
      },
    });
  }

  return (
    <div className={styles.stack}>
      {advance.isPending && (
        <p role="status" className={styles.note}>
          {ADVANCING_TEXT}
        </p>
      )}
      {alertText !== null && (
        <div ref={alertBox} role="alert" tabIndex={-1} className={styles.alert}>
          <p>{alertText}</p>
          {problem?.code === 'RECONCILIATION_FAILED' && (
            <button
              type="button"
              className={styles.secondary}
              disabled={advance.isPending}
              onClick={submit}
            >
              다시 시도
            </button>
          )}
        </div>
      )}
      <button
        type="button"
        className={styles.primary}
        disabled={advance.isPending}
        aria-busy={advance.isPending ? 'true' : undefined}
        onClick={submit}
      >
        {advance.isPending ? '턴을 넘기는 중…' : advanceLabel(season)}
      </button>
    </div>
  );
}

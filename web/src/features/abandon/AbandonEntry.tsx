import { useIsMutating } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import type { Season } from '../seasons/labels.ts';
import { advanceKey } from '../turn/queries.ts';
import { AbandonDialog } from './AbandonDialog.tsx';
import { abandonMode } from './abandonMode.ts';
import {
  ABANDON_BUTTON,
  RESUME_BUTTON,
  WORKING_TEXT,
  failureText,
  leftoverText,
  progressText,
} from './abandonMessages.ts';
import { useAbandonSeason } from './queries.ts';
import styles from './Abandon.module.css';

export function AbandonEntry({
  season,
  onNotice,
  onFinished,
}: {
  season: Pick<Season, 'id' | 'status' | 'currentTurnNo' | 'pathLength'>;
  onNotice: (text: string | null) => void;
  onFinished: (turnNo: number) => void;
}) {
  const { mutation, progress } = useAbandonSeason(season.id, {
    onNotice,
    onFinished,
  });
  const advancing = useIsMutating({ mutationKey: advanceKey(season.id) }) > 0;
  const [confirming, setConfirming] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  const alertBox = useRef<HTMLDivElement>(null);
  // Two taps before the first re-render must send one request.
  const inFlight = useRef(false);
  const mode = abandonMode(season);
  const pending = mutation.isPending;
  const alertText = failureText(mutation.error);

  useEffect(() => {
    if (alertText !== null) alertBox.current?.focus();
  }, [alertText, mutation.error]);

  function send() {
    if (inFlight.current || pending) return;
    inFlight.current = true;
    onNotice(null);
    // The turn the screen shows: the server rejects it if it is out of date.
    mutation.mutate(season.currentTurnNo, {
      onSettled: () => {
        inFlight.current = false;
        setConfirming(false);
      },
    });
  }

  // A season that left the abandonable states keeps showing why a call failed.
  if (mode === null && alertText === null) return null;

  const workingText =
    progress === null
      ? WORKING_TEXT
      : progressText(
          progress.currentTurnNo,
          progress.pathLength,
          progress.remainingTurns,
        );
  const leftover = mutation.data;

  return (
    <section className={styles.entry} aria-label="시즌 포기">
      {pending && !confirming && (
        <p role="status" className={styles.note}>
          {workingText}
        </p>
      )}
      {alertText !== null && (
        <div ref={alertBox} role="alert" tabIndex={-1} className={styles.alert}>
          <p>{alertText}</p>
        </div>
      )}
      {mode === null ? null : mode === 'resume' ? (
        <>
          {leftover?.status === 'ABANDONING' && !pending && (
            <p className={styles.note}>
              {leftoverText(leftover.remainingTurns)}
            </p>
          )}
          <button
            type="button"
            className={styles.primary}
            disabled={pending}
            aria-busy={pending ? 'true' : undefined}
            onClick={send}
          >
            {pending ? '정리하는 중…' : RESUME_BUTTON}
          </button>
        </>
      ) : (
        <button
          ref={opener}
          type="button"
          className={styles.secondary}
          disabled={advancing || pending}
          onClick={() => {
            setConfirming(true);
          }}
        >
          {ABANDON_BUTTON}
        </button>
      )}
      {confirming && (
        <AbandonDialog
          remainingTurns={season.pathLength - season.currentTurnNo}
          pending={pending}
          progress={pending ? workingText : null}
          onConfirm={send}
          onClose={() => {
            setConfirming(false);
            opener.current?.focus();
          }}
        />
      )}
    </section>
  );
}

import { useIsMutating } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import type { Season } from '../seasons/labels.ts';
import { advanceKey } from '../turn/queries.ts';
import { AbandonDialog } from './AbandonDialog.tsx';
import { abandonMode } from './abandonMode.ts';
import {
  MENU_BUTTON_ID,
  RESUME_BUTTON,
  WORKING_TEXT,
  failureText,
  isStaleCode,
  leftoverText,
  progressText,
} from './abandonMessages.ts';
import { useAbandonSeason } from './queries.ts';
import styles from './Abandon.module.css';

export function AbandonEntry({
  season,
  onNotice,
  onFinished,
  openRequest = false,
  onOpenHandled,
}: {
  season: Pick<Season, 'id' | 'status' | 'currentTurnNo' | 'pathLength'>;
  onNotice: (text: string | null) => void;
  onFinished: (turnNo: number) => void;
  // Set when the header menu asked for the confirmation step.
  openRequest?: boolean;
  onOpenHandled?: () => void;
}) {
  const { mutation, progress } = useAbandonSeason(season.id, {
    onNotice,
    onFinished,
  });
  const advancing = useIsMutating({ mutationKey: advanceKey(season.id) }) > 0;
  const [confirming, setConfirming] = useState(false);
  const alertBox = useRef<HTMLDivElement>(null);
  // Two taps before the first re-render must send one request.
  const inFlight = useRef(false);
  const mode = abandonMode(season);
  const pending = mutation.isPending;
  const alertText = failureText(mutation.error);

  useEffect(() => {
    if (alertText !== null) alertBox.current?.focus();
  }, [alertText, mutation.error]);

  // The opener is the header menu button: after the dialog closes, and after
  // a stale refusal, focus goes back there instead of falling to the document.
  const refocus = useRef(false);
  useEffect(() => {
    if (!confirming && refocus.current) {
      refocus.current = false;
      focusMenuButton();
    }
  }, [confirming]);

  // The request opens the step at once (state set while rendering, guarded);
  // the page clears it right after, so it cannot open a second time.
  const busy = advancing || pending;
  if (openRequest && mode === 'start' && !busy && !confirming)
    setConfirming(true);
  useEffect(() => {
    if (openRequest) onOpenHandled?.();
  }, [openRequest, onOpenHandled]);

  function send() {
    if (inFlight.current || pending) return;
    inFlight.current = true;
    onNotice(null);
    // The turn the screen shows: the server rejects it if it is out of date.
    mutation.mutate(season.currentTurnNo, {
      onError: (error) => {
        if (isStaleCode(error)) refocus.current = true;
      },
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

  const hasEntry =
    alertText !== null || mode === 'resume' || (pending && !confirming);

  return (
    <>
      {hasEntry && (
        <section className={styles.entry} aria-label="시즌 포기">
          {pending && !confirming && (
            <p role="status" className={styles.note}>
              {workingText}
            </p>
          )}
          {alertText !== null && (
            <div
              ref={alertBox}
              role="alert"
              tabIndex={-1}
              className={styles.alert}
            >
              <p>{alertText}</p>
            </div>
          )}
          {mode === 'resume' && (
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
          )}
        </section>
      )}
      {confirming && (
        <AbandonDialog
          remainingTurns={season.pathLength - season.currentTurnNo}
          pending={pending}
          progress={pending ? workingText : null}
          onConfirm={send}
          onClose={() => {
            setConfirming(false);
            focusMenuButton();
          }}
        />
      )}
    </>
  );
}

function focusMenuButton() {
  document.getElementById(MENU_BUTTON_ID)?.focus();
}

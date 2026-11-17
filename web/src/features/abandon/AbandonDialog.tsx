import { useEffect, useRef } from 'react';
import {
  CONFIRM_BUTTON,
  DIALOG_TITLE,
  KEEP_BUTTON,
  rulesText,
} from './abandonMessages.ts';
import styles from './Abandon.module.css';

export function AbandonDialog({
  remainingTurns,
  pending,
  progress,
  onConfirm,
  onClose,
}: {
  remainingTurns: number;
  pending: boolean;
  // The working sentence while the request runs.
  progress: string | null;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const keep = useRef<HTMLButtonElement>(null);

  // No cleanup: closing here would fire `close` during a development
  // StrictMode re-run and dismiss the dialog. Unmounting removes a modal.
  useEffect(() => {
    const element = dialog.current;
    if (element !== null && !element.open) element.showModal();
    // The safe choice takes focus first.
    keep.current?.focus();
  }, []);

  function dismiss() {
    const element = dialog.current;
    if (element?.open) element.close();
    else onClose();
  }

  return (
    <dialog
      ref={dialog}
      className={styles.sheet}
      aria-labelledby="abandon-dialog-title"
      aria-describedby="abandon-dialog-rules"
      onClose={onClose}
      onCancel={(event) => {
        // A request that is running cannot be cancelled by closing.
        if (pending) event.preventDefault();
      }}
    >
      <div className={styles.sheetBody}>
        <h2 id="abandon-dialog-title" className={styles.dialogHeading}>
          {DIALOG_TITLE}
        </h2>
        <ul id="abandon-dialog-rules" className={styles.rules}>
          {rulesText(remainingTurns).map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
        {progress !== null && (
          <p role="status" className={styles.note}>
            {progress}
          </p>
        )}
        <div className={styles.actions}>
          <button
            ref={keep}
            type="button"
            className={styles.secondary}
            disabled={pending}
            onClick={dismiss}
          >
            {KEEP_BUTTON}
          </button>
          <button
            type="button"
            className={styles.danger}
            disabled={pending}
            onClick={onConfirm}
          >
            {CONFIRM_BUTTON}
          </button>
        </div>
      </div>
    </dialog>
  );
}

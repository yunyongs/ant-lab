import type { InputHTMLAttributes, Ref } from 'react';
import { messageFor, UNREACHABLE_TEXT } from '../api/errorMessages.ts';
import type { AuthFailure } from '../features/auth/authApi.ts';
import styles from './AuthPage.module.css';

export function AuthAlert({
  title,
  failure,
  show,
}: {
  title: string;
  failure: AuthFailure | null;
  show: boolean;
}) {
  if (failure === null || !show) return null;
  const text =
    failure.kind === 'unreachable'
      ? UNREACHABLE_TEXT
      : messageFor(failure.code);
  return (
    <div role="alert" className={styles.alert}>
      <p className={styles.alertTitle}>{title}</p>
      <p>{text}</p>
      {failure.kind === 'problem' && (
        <p className={styles.code}>
          {failure.code} · {String(failure.status)}
        </p>
      )}
    </div>
  );
}

export function TextField({
  id,
  label,
  hint,
  error,
  inputRef,
  ...input
}: {
  id: string;
  label: string;
  hint: string;
  error?: string | undefined;
  inputRef?: Ref<HTMLInputElement>;
} & Omit<InputHTMLAttributes<HTMLInputElement>, 'id'>) {
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      <input
        {...input}
        id={id}
        ref={inputRef}
        className={styles.input}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${errorId} ${hintId}` : hintId}
      />
      {error && (
        <p id={errorId} className={styles.fieldError}>
          {error}
        </p>
      )}
      <p id={hintId} className={styles.hint}>
        {hint}
      </p>
    </div>
  );
}

export function Notice({ text }: { text: string }) {
  return (
    <p role="status" className={styles.notice}>
      {text}
    </p>
  );
}

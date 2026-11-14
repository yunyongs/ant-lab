import { messageFor, UNREACHABLE_TEXT } from '../../api/errorMessages.ts';
import { ProblemError } from '../../api/problem.ts';
import styles from './Seasons.module.css';

// A 401 shows nothing here: the session guard moves the visitor to the login
// screen.
export function QueryError({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry: () => void;
}) {
  if (error instanceof ProblemError) {
    if (error.code === 'UNAUTHENTICATED') return null;
    return (
      <div role="alert" className={styles.alert}>
        <p>{messageFor(error.code)}</p>
        <button type="button" className={styles.secondary} onClick={onRetry}>
          다시 불러오기
        </button>
      </div>
    );
  }
  return (
    <div role="alert" className={styles.alert}>
      <p>{UNREACHABLE_TEXT}</p>
      <button type="button" className={styles.secondary} onClick={onRetry}>
        다시 불러오기
      </button>
    </div>
  );
}

import { messageFor, UNREACHABLE_TEXT } from '../../api/errorMessages.ts';
import { ProblemError } from '../../api/problem.ts';
import styles from './Team.module.css';

function lineFor(error: unknown): string | null {
  if (error === null || error === undefined) return '종목 풀이 비어 있습니다.';
  if (error instanceof ProblemError) {
    return error.code === 'UNAUTHENTICATED' ? null : messageFor(error.code);
  }
  return UNREACHABLE_TEXT;
}

export function TeamEmpty({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry: () => void;
}) {
  const line = lineFor(error);
  if (line === null) return null;
  return (
    <div role="alert" className={styles.alert}>
      <strong>종목 풀을 불러오지 못했습니다</strong>
      <p className={styles.noMargin}>{line}</p>
      <button type="button" className={styles.secondary} onClick={onRetry}>
        다시 불러오기
      </button>
    </div>
  );
}

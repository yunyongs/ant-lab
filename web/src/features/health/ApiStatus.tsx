import { useApiStatus } from './useApiStatus.ts';
import styles from './ApiStatus.module.css';

export function ApiStatus({ compact = false }: { compact?: boolean }) {
  const { data, isPending } = useApiStatus();
  let text = 'API에 연결할 수 없습니다';
  let short = '✕ 연결 불가';
  let tone = styles.bad;
  if (isPending) {
    text = 'API 연결 확인 중…';
    short = '… 확인 중';
    tone = styles.pending;
  } else if (data?.kind === 'connected') {
    text = `API 연결됨 · 종목 ${data.assetCount.toString()}개`;
    short = '✓ 연결됨';
    tone = styles.ok;
  } else if (data?.kind === 'apiError') {
    text = `API 연결됨 · 응답 오류 ${data.code}`;
    short = '! 응답 오류';
    tone = styles.warn;
  }
  if (compact) {
    return (
      <p role="status" className={`${styles.compact} ${tone}`} title={text}>
        <span className={styles.dot} aria-hidden="true" />
        <span className={styles.shortText} aria-hidden="true">
          {short}
        </span>
        <span className={styles.srOnly}>{text}</span>
      </p>
    );
  }
  return (
    <p role="status" className={`${styles.status} ${tone}`}>
      {text}
    </p>
  );
}

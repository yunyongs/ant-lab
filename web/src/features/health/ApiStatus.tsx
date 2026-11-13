import { useApiStatus } from './useApiStatus.ts';
import styles from './ApiStatus.module.css';

export function ApiStatus() {
  const { data, isPending } = useApiStatus();
  let text = 'API에 연결할 수 없습니다';
  let tone = styles.bad;
  if (isPending) {
    text = 'API 연결 확인 중…';
    tone = styles.pending;
  } else if (data?.kind === 'connected') {
    text = `API 연결됨 · 종목 ${data.assetCount.toString()}개`;
    tone = styles.ok;
  } else if (data?.kind === 'apiError') {
    text = `API 연결됨 · 응답 오류 ${data.code}`;
    tone = styles.warn;
  }
  return (
    <p role="status" className={`${styles.status} ${tone}`}>
      {text}
    </p>
  );
}

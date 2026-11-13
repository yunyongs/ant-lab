import { useRouteError } from 'react-router';
import styles from '../pages/Page.module.css';

export function RouteError() {
  const error = useRouteError();
  console.error(error);
  return (
    <div className={styles.page} role="alert">
      <h1 className={styles.heading}>문제가 발생했습니다</h1>
      <p>화면을 표시하는 중 오류가 났습니다. 새로고침 후 다시 시도해 주세요.</p>
    </div>
  );
}

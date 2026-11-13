import { ApiStatus } from '../features/health/ApiStatus.tsx';
import styles from './Page.module.css';

export function HomePage() {
  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>홈</h1>
      <ApiStatus />
    </div>
  );
}

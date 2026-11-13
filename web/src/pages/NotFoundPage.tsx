import { Link } from 'react-router';
import styles from './Page.module.css';

export function NotFoundPage() {
  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>페이지를 찾을 수 없습니다</h1>
      <Link to="/" className={styles.link}>
        홈으로 돌아가기
      </Link>
    </div>
  );
}

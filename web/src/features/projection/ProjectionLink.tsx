import { Link } from 'react-router';
import styles from './Projection.module.css';

// A plain link: following it is what requests the projection, never rendering.
export function ProjectionLink({ seasonId }: { seasonId: number }) {
  return (
    <Link
      className={styles.entryLink}
      to={`/seasons/${String(seasonId)}/projection`}
    >
      예상 분포 보기
    </Link>
  );
}

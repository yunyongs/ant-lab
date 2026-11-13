import { Link } from 'react-router';
import { QueryError } from '../features/seasons/QueryError.tsx';
import { SeasonCard } from '../features/seasons/SeasonCard.tsx';
import { useSeasons } from '../features/seasons/queries.ts';
import seasons from '../features/seasons/Seasons.module.css';
import styles from './Page.module.css';

export function SeasonsPage() {
  const query = useSeasons();
  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>내 시즌</h1>
      {query.isPending && (
        <p role="status" className={seasons.note}>
          시즌을 불러오는 중입니다…
        </p>
      )}
      {query.isError && (
        <QueryError
          error={query.error}
          onRetry={() => {
            void query.refetch();
          }}
        />
      )}
      {query.data && query.data.length === 0 && (
        <div className={seasons.empty}>
          <p className={seasons.emptyTitle}>아직 시즌이 없습니다</p>
          <p>
            시즌을 시작하면 실제 역사의 달을 이어 붙인 가상의 시장에서 펀드를
            운용합니다. 위험 선언과 포메이션을 정하고 팀을 꾸려 보세요.
          </p>
          <Link to="/seasons/new" className={seasons.primary}>
            첫 시즌 시작
          </Link>
        </div>
      )}
      {query.data && query.data.length > 0 && (
        <>
          <Link to="/seasons/new" className={seasons.primary}>
            새 시즌 시작
          </Link>
          <ul className={seasons.list}>
            {query.data.map((season) => (
              <SeasonCard key={season.id} season={season} />
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

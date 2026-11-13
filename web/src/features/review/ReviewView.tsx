import { Link } from 'react-router';
import type { Season } from '../seasons/labels.ts';
import { FormationChangeList } from './FormationChangeList.tsx';
import type { Review, TurnValues } from './queries.ts';
import { ReproCard } from './ReproCard.tsx';
import { TurnReviewList } from './TurnReviewList.tsx';
import styles from './Review.module.css';

export function ReviewView({
  review,
  season,
  values,
}: {
  review: Review;
  season: Season;
  values: TurnValues;
}) {
  const base = `/seasons/${String(season.id)}`;
  return (
    <div className={styles.stack}>
      <h1 className={styles.heading}>{`시즌 ${String(season.id)} 복기`}</h1>
      {review.abandonedTurnNo !== null && (
        <p className={styles.note}>
          {`${String(review.abandonedTurnNo)}턴에서 포기한 시즌입니다.`}
        </p>
      )}
      <ReproCard review={review} />
      <TurnReviewList
        review={review}
        lastTurnNo={season.currentTurnNo}
        values={values}
      />
      <FormationChangeList changes={review.formationChanges} />
      <div className={styles.entries}>
        <Link className={styles.secondary} to={`${base}/report`}>
          성적표 보기
        </Link>
        <Link className={styles.link} to={base}>
          턴으로 돌아가기
        </Link>
      </div>
    </div>
  );
}

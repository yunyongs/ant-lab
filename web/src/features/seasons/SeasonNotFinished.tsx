import { Link } from 'react-router';
import type { Season } from './labels.ts';
import styles from './Seasons.module.css';

const TEXTS = {
  report: {
    title: '시즌이 끝나면 성적표가 열립니다',
    body: (current: string, length: string) =>
      `지금은 ${current}/${length}턴입니다. 마지막 턴까지 진행하면 결과·위험 준수·과정·비용·대사를 한 장으로 볼 수 있습니다.`,
    abandoning: '정리 중인 시즌이라 아직 성적표를 볼 수 없습니다.',
  },
  review: {
    title: '시즌이 끝나면 복기가 열립니다',
    body: () =>
      '복기에는 각 턴의 실제 연월과 사건이 나옵니다. 진행 중에 보면 판단에 영향을 주므로 시즌이 끝나기 전에는 보이지 않습니다.',
    abandoning: '정리 중인 시즌이라 아직 복기를 볼 수 없습니다.',
  },
} as const;

// The calm "not yet" view: a status, never an alert.
export function SeasonNotFinished({
  season,
  screen,
}: {
  season: Season;
  screen: 'report' | 'review';
}) {
  const text = TEXTS[screen];
  const abandoning = season.status === 'ABANDONING';
  return (
    <div className={styles.stack}>
      <div role="status" className={styles.pending}>
        {abandoning ? (
          <p>{text.abandoning}</p>
        ) : (
          <>
            <p className={styles.emptyTitle}>{text.title}</p>
            <p>
              {text.body(
                String(season.currentTurnNo),
                String(season.pathLength),
              )}
            </p>
          </>
        )}
      </div>
      <Link className={styles.secondary} to={`/seasons/${String(season.id)}`}>
        턴으로 돌아가기
      </Link>
    </div>
  );
}

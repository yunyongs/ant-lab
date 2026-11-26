import { useState } from 'react';
import { Link } from 'react-router';
import { formatDecimalFixed } from '../features/format/decimal.ts';
import { ratioToPercent } from '../features/format/ratio.ts';
import {
  useManagerLeaderboard,
  type Leaderboard,
  type LeaderboardKind,
} from '../features/leaderboard/queries.ts';
import { QueryError } from '../features/seasons/QueryError.tsx';
import type { components } from '../api/schema';
import styles from './LeaderboardPage.module.css';
import pageStyles from './Page.module.css';

const TABS: readonly { kind: LeaderboardKind; label: string }[] = [
  { kind: 'ALL', label: '전체' },
  { kind: 'HUMAN', label: '사람' },
  { kind: 'AGENT', label: '에이전트' },
];

type Row = components['schemas']['ManagerLeaderboardRowDto'];
type Counts = components['schemas']['ManagerSeasonCountsDto'];
type MySeason = components['schemas']['ManagerLeaderboardMySeasonDto'];
type DetectionMetric = components['schemas']['DetectionMetric'];

const detectionMetricLabels: Record<DetectionMetric, string> = {
  YM_EXACT: '연월 정확',
  YM_WITHIN_3: '연월 ±3개월',
  YEAR: '연도',
  KR_NEXT: '다음 달 한국 방향',
  US_NEXT: '다음 달 미국 방향',
};

const countLabels: readonly [keyof Counts, string][] = [
  ['voided', '무효'],
  ['unfinished', '진행 중'],
  ['awaitingLeagueClose', '리그 종료 대기'],
  ['pending', '계산 대기'],
  ['notStarted', '미시작'],
  ['mismatch', '기준 불일치'],
  ['noReference', '기준 없음'],
];

const excludedText: Record<Exclude<MySeason['excluded'], null>, string> = {
  NOT_STARTED: '3턴 전에 포기해 제외',
  REFERENCE_MISMATCH: '기준 계산 불일치로 제외',
  NO_REFERENCE: '기준 집합을 만들 수 없어 제외',
  PENDING: '점수 계산 대기 중',
  AWAITING_LEAGUE_CLOSE: '리그가 닫히면 반영',
};

function CountsLine({ counts }: { counts: Counts }) {
  const parts = countLabels
    .filter(([key]) => counts[key] > 0)
    .map(([key, label]) => `${label} ${String(counts[key])}`);
  return parts.length > 0 ? (
    <p className={styles.counts}>{parts.join(' · ')}</p>
  ) : null;
}

function Detection({
  detection,
}: {
  detection: NonNullable<Row['agent']>['detection'];
}) {
  if (detection.status === 'NOT_TESTED') {
    return <p className={styles.detail}>탐지 시험 전</p>;
  }
  return (
    <div className={styles.detection}>
      {detection.windows?.map((win) => (
        <span key={win.months}>
          {win.months}개월 · {win.units}단위 ·{' '}
          {win.metrics
            .map(
              (metric) =>
                `${detectionMetricLabels[metric.metric]} 정확도 ${ratioToPercent(metric.accuracy)} / 기준 ${ratioToPercent(metric.baseRate)}`,
            )
            .join(', ')}
        </span>
      ))}
    </div>
  );
}

function LeaderboardRow({ row }: { row: Row }) {
  return (
    <li className={styles.card}>
      <div className={styles.nameLine}>
        <span className={styles.rank}>{row.rank}위</span>
        <h2 className={styles.cardTitle}>{row.displayName}</h2>
        {row.kind === 'AGENT' && <span className={styles.kind}>에이전트</span>}
        {row.me && <span className={styles.mine}>나</span>}
        {row.ownedByMe && <span className={styles.mine}>내 에이전트</span>}
      </div>
      <div className={styles.metricLine}>
        <span className={styles.score}>L {formatDecimalFixed(row.l, 2)}</span>
        <span>{row.seasonsUsed}시즌</span>
        <span>평균 평가 배수 {formatDecimalFixed(row.meanMultiple, 2)}배</span>
      </div>
      <CountsLine counts={row.counts} />
      {row.agent && (
        <div className={styles.detail}>
          <div>
            페르소나{' '}
            <span className={styles.agentText}>{row.agent.personaName}</span> v
            {row.agent.personaVersion} · 모델{' '}
            <span className={styles.agentText}>{row.agent.modelId}</span> ·{' '}
            {row.agent.memoryVersion === null
              ? '기억 없음'
              : `기억 v${String(row.agent.memoryVersion)}`}
          </div>
          <div>응답 실패 {row.agent.failedTurns}턴, 모든 시즌</div>
          <Detection detection={row.agent.detection} />
          {row.agent.outlook === null && <div>전망 기록 없음</div>}
        </div>
      )}
    </li>
  );
}

function MyProgress({ me }: { me: Leaderboard['me'] }) {
  if (me === null) return <p className={styles.empty}>아직 시즌이 없습니다</p>;
  return (
    <section className={styles.meCard} aria-labelledby="my-progress-title">
      <h2 id="my-progress-title" className={styles.sectionTitle}>
        내 진행
      </h2>
      <p className={styles.note}>
        {me.ranked
          ? `집계 ${me.counted}시즌`
          : `${me.minimum}시즌 중 ${me.counted}시즌`}
      </p>
      {me.ranked ? (
        <p className={styles.metricLine}>
          {me.rank !== null && <>순위 {me.rank}위 · </>}
          {me.l !== null && <>L {formatDecimalFixed(me.l, 2)}</>}
        </p>
      ) : (
        <p className={styles.muted}>아직 순위에 들지 않았습니다</p>
      )}
      {me.seasons.length > 0 && (
        <ul className={styles.meSeasonList}>
          {me.seasons.map((season) => (
            <MySeasonRow key={season.seasonNo} season={season} />
          ))}
        </ul>
      )}
    </section>
  );
}

function MySeasonRow({ season }: { season: MySeason }) {
  const result =
    season.excluded === null
      ? [
          season.s === null ? null : formatDecimalFixed(season.s, 2),
          season.multiple === null
            ? null
            : `${formatDecimalFixed(season.multiple, 2)}배`,
          !season.used && 'L 제외(최근 10시즌 밖)',
        ]
          .filter((part): part is string => part !== null && part !== false)
          .join(' · ')
      : excludedText[season.excluded];
  return (
    <li className={styles.meSeason}>
      <span>시즌 {season.seasonNo}</span>
      <span>{result}</span>
    </li>
  );
}

export function LeaderboardPage() {
  const [kind, setKind] = useState<LeaderboardKind>('ALL');
  const query = useManagerLeaderboard(kind);
  const data = query.data;
  return (
    <div className={pageStyles.page}>
      <div className={pageStyles.headingRow}>
        <h1 className={pageStyles.heading}>리더보드</h1>
        {data?.formula.status === 'PROVISIONAL' && (
          <span className={styles.badge}>잠정 공식 {data.formula.version}</span>
        )}
      </div>
      <div className={styles.tabs} role="group" aria-label="리더보드 종류">
        {TABS.map((tab) => (
          <button
            key={tab.kind}
            type="button"
            className={styles.tab}
            aria-pressed={kind === tab.kind}
            onClick={() => {
              setKind(tab.kind);
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>
      {query.isPending && (
        <p role="status" className={styles.note}>
          리더보드를 불러오는 중입니다…
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
      {data && (
        <>
          {data.rows.length > 0 ? (
            <ol
              className={`${styles.list} ${query.isPlaceholderData ? styles.loading : ''}`}
              aria-label="순위 목록"
              aria-busy={query.isPlaceholderData}
            >
              {data.rows.map((row, index) => (
                <LeaderboardRow key={index} row={row} />
              ))}
            </ol>
          ) : (
            <div className={styles.card}>
              <p className={styles.empty}>아직 순위에 든 매니저가 없습니다</p>
              {data.pendingSeasons > 0 && (
                <p className={styles.note}>
                  점수 계산 대기 {data.pendingSeasons}시즌
                </p>
              )}
            </div>
          )}
          <p className={styles.note}>
            순위 밖 사람 {data.unranked.humans}명 · 에이전트{' '}
            {data.unranked.agents}명 (최소 시즌 미달)
          </p>
          <MyProgress me={data.me} />
        </>
      )}
      <Link to="/" className={pageStyles.link}>
        내 시즌
      </Link>
    </div>
  );
}

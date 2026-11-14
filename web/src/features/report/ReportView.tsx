import { Link } from 'react-router';
import { isNavEnabled } from '../../app/navItems.ts';
import type { Season } from '../seasons/labels.ts';
import type { Report } from './queries.ts';
import { ReportGroup } from './ReportGroup.tsx';
import { LIMITS, LIMITS_TITLE } from './reportMessages.ts';
import { GROUPS } from './reportRows.ts';
import styles from './Report.module.css';

export function ReportView({
  report,
  season,
}: {
  report: Report;
  season: Season;
}) {
  const base = `/seasons/${String(season.id)}`;
  return (
    <div className={styles.stack}>
      <h1 className={styles.heading}>
        {`시즌 ${String(season.id)} 성적표 (${report.isFinal ? '확정' : '잠정'})`}
      </h1>
      <p className={styles.meta}>
        {`${String(report.reportTurns)}/${String(report.pathLength)}턴 보고 · 보류 주문 ${String(report.pendingOrders)}건 · 보류 전술 변경 ${String(report.pendingChanges)}건`}
      </p>
      {GROUPS.map((group) => (
        <ReportGroup key={group.id} group={group} report={report} />
      ))}
      <details className={styles.limits}>
        <summary className={styles.summary}>{LIMITS_TITLE}</summary>
        <ul className={styles.limitList}>
          {LIMITS.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      </details>
      <div className={styles.entries}>
        {isNavEnabled('review') ? (
          <Link className={styles.secondary} to={`${base}/review`}>
            복기 보기
          </Link>
        ) : (
          <span
            aria-disabled="true"
            className={`${styles.secondary} ${styles.off}`}
          >
            복기 보기 (준비 중)
          </span>
        )}
        <Link className={styles.link} to={base}>
          턴으로 돌아가기
        </Link>
      </div>
    </div>
  );
}

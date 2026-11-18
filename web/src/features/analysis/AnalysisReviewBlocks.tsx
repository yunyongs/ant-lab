import { QueryError } from '../seasons/QueryError.tsx';
import {
  ANALYSIS_LOADING_TEXT,
  REVIEW_FACTS_TITLE,
} from './analysisMessages.ts';
import { AttentionBlock } from './AttentionBlock.tsx';
import { DefenderBlock } from './DefenderBlock.tsx';
import { useAnalysisState } from './useAnalysisState.ts';
import styles from './Analysis.module.css';

export function AnalysisReviewBlocks({ seasonId }: { seasonId: number }) {
  const { analysis, stale } = useAnalysisState(seasonId);
  if (stale) return null;
  return (
    <section className={styles.group} aria-labelledby="review-facts">
      <h2 id="review-facts" className={styles.sectionHeading}>
        {REVIEW_FACTS_TITLE}
      </h2>
      {analysis.isPending && (
        <p role="status" className={styles.status}>
          {ANALYSIS_LOADING_TEXT}
        </p>
      )}
      {analysis.isError && (
        <QueryError
          error={analysis.error}
          onRetry={() => {
            void analysis.refetch();
          }}
        />
      )}
      {analysis.data && (
        <>
          <AttentionBlock attention={analysis.data.attentionFollowing} />
          <DefenderBlock defender={analysis.data.defenderGoals} />
        </>
      )}
    </section>
  );
}

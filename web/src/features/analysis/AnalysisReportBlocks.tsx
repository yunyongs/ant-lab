import { QueryError } from '../seasons/QueryError.tsx';
import { ANALYSIS_LOADING_TEXT } from './analysisMessages.ts';
import { ChangeEffectBlock } from './ChangeEffectBlock.tsx';
import { NoiseBlock } from './NoiseBlock.tsx';
import { PlatformBlock } from './PlatformBlock.tsx';
import { useAnalysisState } from './useAnalysisState.ts';
import styles from './Analysis.module.css';

// Rendered only inside a finished season's report; its own request fails
// alone and leaves the report rows readable.
export function AnalysisReportBlocks({ seasonId }: { seasonId: number }) {
  const { analysis, stale } = useAnalysisState(seasonId);
  if (stale) return null;
  if (analysis.isPending) {
    return (
      <p role="status" className={styles.status}>
        {ANALYSIS_LOADING_TEXT}
      </p>
    );
  }
  if (analysis.isError) {
    return (
      <QueryError
        error={analysis.error}
        onRetry={() => {
          void analysis.refetch();
        }}
      />
    );
  }
  return (
    <>
      <NoiseBlock noise={analysis.data.noise} />
      <PlatformBlock platform={analysis.data.platform} />
      <ChangeEffectBlock effects={analysis.data.changeEffects} />
    </>
  );
}

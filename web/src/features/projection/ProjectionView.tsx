import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useState } from 'react';
import { messageFor } from '../../api/errorMessages.ts';
import { ProblemError } from '../../api/problem.ts';
import { formatWon } from '../format/money.ts';
import { QueryError } from '../seasons/QueryError.tsx';
import type { Season } from '../seasons/labels.ts';
import { invalidateSeasonState } from '../seasons/queries.ts';
import { BandChart } from './BandChart.tsx';
import { phaseLabel, phasesFor, type ProjectionPhase } from './phases.ts';
import {
  captionText,
  ENGINE_BUSY_TEXT,
  HISTORY_TOO_SHORT_TEXT,
  PROJECTION_LOADING_TEXT,
  SURVIVORSHIP_TEXT,
} from './projectionMessages.ts';
import { useProjection } from './queries.ts';
import { SummaryList } from './SummaryList.tsx';
import styles from './Projection.module.css';

function PhasePicker({
  phases,
  value,
  onChange,
}: {
  phases: ProjectionPhase[];
  value: ProjectionPhase;
  onChange: (phase: ProjectionPhase) => void;
}) {
  const name = useId();
  return (
    <fieldset className={styles.phases}>
      <legend>어느 팀을 볼까요?</legend>
      {phases.map((phase) => (
        <label key={phase} className={styles.phase}>
          <input
            type="radio"
            name={name}
            checked={phase === value}
            onChange={() => {
              onChange(phase);
            }}
          />
          {phaseLabel[phase]}
        </label>
      ))}
    </fieldset>
  );
}

// Requests one projection for the phase the season's state allows, and only
// while this view is on screen. The caption and the survivorship sentence stay
// on every state, including loading and failures.
export function ProjectionView({ season }: { season: Season }) {
  const queryClient = useQueryClient();
  const phases = phasesFor(season);
  const [chosen, setChosen] = useState<ProjectionPhase | null>(null);
  const phase = chosen !== null && phases.includes(chosen) ? chosen : phases[0];
  const projection = useProjection(
    season.id,
    phase ?? 'CURRENT',
    season.currentTurnNo,
    phase !== undefined,
  );
  const error = projection.error;
  const problem = error instanceof ProblemError ? error : null;
  const stale = problem !== null && problem.status === 409;

  // The page asked for a moment the server no longer allows: follow the
  // server's state instead.
  useEffect(() => {
    if (stale) void invalidateSeasonState(queryClient, season.id);
  }, [stale, queryClient, season.id]);

  const data = projection.data;
  return (
    <div className={styles.view}>
      <p className={styles.caption}>
        {captionText(data === undefined ? null : data.pathCount)}
      </p>
      {phase !== undefined && phases.length > 1 && (
        <PhasePicker phases={phases} value={phase} onChange={setChosen} />
      )}
      {projection.isPending && projection.fetchStatus !== 'idle' && (
        <p role="status" className={styles.note}>
          {PROJECTION_LOADING_TEXT}
        </p>
      )}
      {problem?.code === 'ENGINE_UNAVAILABLE' && (
        <div role="alert" className={styles.alert}>
          <p>{ENGINE_BUSY_TEXT}</p>
          <button
            type="button"
            className={styles.retry}
            onClick={() => {
              void projection.refetch();
            }}
          >
            다시 시도
          </button>
        </div>
      )}
      {problem?.code === 'PATH_TOO_LONG' && (
        <div role="alert" className={styles.alert}>
          <p>{HISTORY_TOO_SHORT_TEXT}</p>
        </div>
      )}
      {stale && (
        <div role="alert" className={styles.alert}>
          <p>{messageFor(problem.code)}</p>
        </div>
      )}
      {problem?.code === 'NOT_FOUND' && (
        <div role="alert" className={styles.alert}>
          <p>시즌을 찾을 수 없습니다.</p>
        </div>
      )}
      {projection.isError &&
        !stale &&
        problem?.code !== 'ENGINE_UNAVAILABLE' &&
        problem?.code !== 'PATH_TOO_LONG' &&
        problem?.code !== 'NOT_FOUND' && (
          <QueryError
            error={error}
            onRetry={() => {
              void projection.refetch();
            }}
          />
        )}
      {data !== undefined && (
        <>
          <p className={styles.basis}>
            {`${phaseLabel[data.phase]} · ${String(data.turnNo)}턴 · 총 평가액 ${formatWon(data.totalValue)}`}
          </p>
          <BandChart projection={data} />
          <SummaryList projection={data} />
        </>
      )}
      <p className={styles.survivorship}>{SURVIVORSHIP_TEXT}</p>
    </div>
  );
}

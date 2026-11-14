import { useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { SeasonForm } from '../features/seasons/SeasonForm.tsx';
import {
  seasonKey,
  seasonsKey,
  useStartSeason,
  type StartSeasonRequest,
} from '../features/seasons/queries.ts';
import styles from './Page.module.css';

export function NewSeasonPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const start = useStartSeason();
  // A second tap before the first answer arrives must not send a second POST.
  const inFlight = useRef(false);

  function onSubmit(body: StartSeasonRequest) {
    if (inFlight.current) return;
    inFlight.current = true;
    start.mutate(body, {
      onSuccess: (season) => {
        queryClient.setQueryData(seasonKey(season.id), season);
        void queryClient.invalidateQueries({ queryKey: seasonsKey });
        void navigate(`/seasons/${String(season.id)}`, { replace: true });
      },
      onSettled: () => {
        inFlight.current = false;
      },
    });
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>새 시즌</h1>
      <SeasonForm
        pending={start.isPending}
        error={start.error}
        onSubmit={onSubmit}
      />
    </div>
  );
}

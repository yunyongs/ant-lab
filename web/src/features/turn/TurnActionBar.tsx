import { Link } from 'react-router';
import type { Season } from '../seasons/labels.ts';
import { AdvanceTurnButton } from './AdvanceTurnButton.tsx';
import styles from './TurnActionBar.module.css';

// Sticks above the bottom menu so the two actions of a turn stay in reach.
export function TurnActionBar({
  season,
  onNotice,
  onAdvanced,
}: {
  season: Season;
  onNotice: (text: string | null) => void;
  onAdvanced: (turnNo: number) => void;
}) {
  return (
    <div className={styles.bar} role="group" aria-label="턴 행동">
      <div className={styles.advance}>
        <AdvanceTurnButton
          season={season}
          onNotice={onNotice}
          onAdvanced={onAdvanced}
        />
      </div>
      <Link className={styles.trade} to={`/seasons/${String(season.id)}/trade`}>
        매매
      </Link>
    </div>
  );
}

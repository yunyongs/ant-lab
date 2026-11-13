import { useIsMutating } from '@tanstack/react-query';
import { Link } from 'react-router';
import type { Season } from '../seasons/labels.ts';
import { canChangeFormation } from '../seasons/seasonGate.ts';
import { advanceKey } from '../turn/queries.ts';
import { TURN_ZERO_TEXT } from './tradeMessages.ts';
import styles from './Trade.module.css';

// The way to the formation page; closed at turn 0 and while a turn advances.
export function FormationEntry({ season }: { season: Season }) {
  const advancing = useIsMutating({ mutationKey: advanceKey(season.id) }) > 0;
  const open = canChangeFormation(season);
  return (
    <div className={styles.entry}>
      {open && !advancing ? (
        <Link
          to={`/seasons/${String(season.id)}/formation`}
          className={styles.entryButton}
        >
          전술 변경
        </Link>
      ) : (
        <span aria-disabled="true" className={styles.entryDisabled}>
          {open ? '전술 변경' : '전술 변경 (1턴부터)'}
        </span>
      )}
      <p className={styles.hint}>
        {open
          ? '전술을 바꾸면 이번 턴에 이어서 내는 주문이 그 변경에 묶입니다.'
          : TURN_ZERO_TEXT}
      </p>
    </div>
  );
}

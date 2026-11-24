import { Link } from 'react-router';
import { useTurnContribution } from '../contribution/queries.ts';
import { Change, SignedValue } from '../format/Change.tsx';
import { subtractDecimal } from '../format/derive.ts';
import { formatWon } from '../format/money.ts';
import { ratioWithPercent } from '../format/ratio.ts';
import { signedChange } from '../format/signedChange.ts';
import {
  leagueText,
  openButtonLabel,
  statusLabel,
  turnText,
  turnProgressWidth,
  type Season,
} from './labels.ts';
import styles from './SeasonCardFeatured.module.css';

// Above this many turns the bar is easier to read than one segment per turn.
const MAX_SEGMENTS = 24;

function TurnProgress({ season }: { season: Season }) {
  const turn = turnText(season);
  const props = {
    role: 'progressbar',
    'aria-label': '현재 턴',
    'aria-valuemin': 0,
    'aria-valuemax': season.pathLength,
    'aria-valuenow': season.currentTurnNo,
    'aria-valuetext': turn,
  };
  if (season.pathLength > 0 && season.pathLength <= MAX_SEGMENTS) {
    return (
      <div className={styles.segments} {...props}>
        {Array.from({ length: season.pathLength }, (_, index) => (
          <span
            key={index}
            className={
              index < season.currentTurnNo
                ? `${styles.segment} ${styles.segmentDone}`
                : styles.segment
            }
          />
        ))}
      </div>
    );
  }
  return (
    <div className={styles.bar} {...props}>
      <span
        className={styles.barFill}
        style={{ width: turnProgressWidth(season) }}
      />
    </div>
  );
}

// The gain is the difference of two shown server figures, worked out exactly;
// a malformed pair hides the line instead of showing a guess.
function GainLine({ season }: { season: Season }) {
  if (season.latestTotalValue === null) return null;
  const gain = subtractDecimal(season.latestTotalValue, season.initialCash);
  if (gain === null) return null;
  const share =
    season.totalReturn === null
      ? null
      : signedChange(season.totalReturn, 'percent');
  return (
    <span className={styles.gain}>
      <SignedValue change={signedChange(gain, 'won')} />
      {share !== null && (
        <span className={styles.share} data-direction={share.direction}>
          {`(${share.text})`}
        </span>
      )}
    </span>
  );
}

function ExcessFact({ season }: { season: Season }) {
  const query = useTurnContribution(
    season.id,
    season.currentTurnNo,
    season.currentTurnNo >= 1,
  );
  if (!query.data) return null;
  return (
    <div className={styles.fact}>
      <dt>기준 대비</dt>
      <dd>
        <Change value={query.data.benchmark.excessReturn} kind="percent" />
      </dd>
    </div>
  );
}

// An in-progress season: value first, then where the turns stand, then the
// way back in.
export function SeasonCardFeatured({ season }: { season: Season }) {
  const league = leagueText(season);
  return (
    <li>
      <article
        className={styles.card}
        aria-label={`진행 중인 시즌 ${String(season.id)}`}
      >
        <div className={styles.title}>
          <h2 className={styles.heading}>{`시즌 ${String(season.id)}`}</h2>
          <span className={styles.status}>
            {`${statusLabel(season)} · ${turnText(season)}`}
            {league !== null && ` · ${league}`}
          </span>
        </div>
        <div className={styles.value}>
          {season.latestTotalValue === null ? (
            <span className={styles.neutral}>시작 전</span>
          ) : (
            <span className={styles.figure}>
              {formatWon(season.latestTotalValue)}
            </span>
          )}
          <GainLine season={season} />
        </div>
        <TurnProgress season={season} />
        <dl className={styles.facts}>
          <div className={styles.fact}>
            <dt>위험 선언</dt>
            <dd>{ratioWithPercent(season.riskLimit)}</dd>
          </div>
          <div className={styles.fact}>
            <dt>현금</dt>
            <dd>{formatWon(season.cashBalance)}</dd>
          </div>
          <ExcessFact season={season} />
        </dl>
        <Link to={`/seasons/${String(season.id)}`} className={styles.open}>
          {openButtonLabel(season)}
        </Link>
      </article>
    </li>
  );
}

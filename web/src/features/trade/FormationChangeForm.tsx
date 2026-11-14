import { useIsMutating } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { messageFor } from '../../api/errorMessages.ts';
import { ProblemError } from '../../api/problem.ts';
import {
  checkFormation,
  FORMATION_PROBLEM_TEXT,
  sameFormation,
  SHARE_KEYS,
  type ShareKey,
  type ShareTexts,
} from '../seasons/formationCheck.ts';
import type { Formation, Season } from '../seasons/labels.ts';
import { advanceKey } from '../turn/queries.ts';
import { UNKNOWN_OUTCOME_TEXT } from '../turn/turnMessages.ts';
import { FormationChangeResult } from './FormationChangeResult.tsx';
import { useChangeFormation, type FormationChange } from './queries.ts';
import {
  REASON_MAX,
  REASON_TOO_LONG_TEXT,
  reasonLength,
  SAME_FORMATION_TEXT,
} from './tradeMessages.ts';
import styles from './Trade.module.css';

const SHARE_LABELS: Record<ShareKey, string> = {
  goalkeeper: '골키퍼',
  defender: '수비수',
  midfielder: '미드필더',
  forward: '공격수',
};

const FIELD_TEXT = {
  formation: '포메이션을 확인해 주세요(합 100, 골키퍼 1 이상).',
  reason: REASON_TOO_LONG_TEXT,
} as const;

type FieldGroup = keyof typeof FIELD_TEXT;

function groupOf(field: string): FieldGroup | null {
  if (field === 'formation' || field.startsWith('formation.')) {
    return 'formation';
  }
  if (field === 'reason') return 'reason';
  return null;
}

interface Failure {
  alert: string | null;
  groups: Partial<Record<FieldGroup, string>>;
}

function failureOf(error: unknown): Failure {
  if (!(error instanceof ProblemError)) {
    // No readable answer: the hook has already asked the server again.
    return { alert: UNKNOWN_OUTCOME_TEXT, groups: {} };
  }
  if (error.code === 'UNAUTHENTICATED') return { alert: null, groups: {} };
  if (error.status === 400 && error.code === 'INVALID_ARGUMENT') {
    const groups: Failure['groups'] = {};
    let unmapped = error.errors.length === 0;
    for (const violation of error.errors) {
      const group = groupOf(violation.field);
      if (group === null) unmapped = true;
      else groups[group] = FIELD_TEXT[group];
    }
    return { alert: unmapped ? messageFor('INVALID_ARGUMENT') : null, groups };
  }
  return { alert: messageFor(error.code), groups: {} };
}

function textsOf(formation: Formation): ShareTexts {
  return {
    goalkeeper: String(formation.goalkeeper),
    defender: String(formation.defender),
    midfielder: String(formation.midfielder),
    forward: String(formation.forward),
  };
}

// The new formation and an optional one-line reason, from turn 1 on. The
// current formation is the server's; the browser only checks what was typed.
export function FormationChangeForm({
  season,
  onNotice,
}: {
  season: Season;
  onNotice: (text: string | null) => void;
}) {
  const baseId = useId();
  const current = season.currentFormation;
  const change = useChangeFormation(season.id, onNotice);
  const advancing = useIsMutating({ mutationKey: advanceKey(season.id) }) > 0;
  const [shares, setShares] = useState<ShareTexts>(textsOf(current));
  const [reason, setReason] = useState('');
  const [result, setResult] = useState<FormationChange | null>(null);
  const [error, setError] = useState<unknown>(null);
  const alertBox = useRef<HTMLDivElement>(null);

  // Derived on every render, not stored.
  const check = checkFormation(shares);
  const same =
    check.formation !== null && sameFormation(check.formation, current);
  const count = reasonLength(reason);
  const reasonOk = count <= REASON_MAX;
  const blocker =
    check.problem !== null
      ? FORMATION_PROBLEM_TEXT[check.problem]
      : same
        ? SAME_FORMATION_TEXT
        : !reasonOk
          ? REASON_TOO_LONG_TEXT
          : null;
  const canSubmit = blocker === null && !change.isPending && !advancing;
  const failure = error === null ? null : failureOf(error);
  const alertText = failure?.alert ?? null;

  useEffect(() => {
    if (alertText !== null) alertBox.current?.focus();
  }, [alertText, error]);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!canSubmit || check.formation === null) return;
    setError(null);
    onNotice(null);
    change.mutate(
      {
        expectedTurnNo: season.currentTurnNo,
        formation: check.formation,
        // A blank reason is left out; any other text goes as typed.
        ...(reason.trim() === '' ? {} : { reason }),
      },
      {
        onSuccess: (done) => {
          setResult(done);
        },
        onError: (caught) => {
          // A stale screen is reported by the page notice, not here.
          if (
            caught instanceof ProblemError &&
            (caught.code === 'TURN_MISMATCH' ||
              caught.code === 'SEASON_NOT_ACTIVE')
          ) {
            return;
          }
          setError(caught);
        },
      },
    );
  }

  if (result !== null) {
    return (
      <FormationChangeResult
        result={result}
        seasonId={season.id}
        onAgain={() => {
          setResult(null);
          setShares(textsOf(season.currentFormation));
          setReason('');
          change.reset();
        }}
      />
    );
  }

  const sumText = check.sum === null ? '–' : String(check.sum);
  const locked = change.isPending || advancing;
  return (
    <form className={styles.card} onSubmit={submit} noValidate>
      <p className={styles.hint}>
        {`전술은 지금 턴(${String(season.currentTurnNo)}턴)에만 바꿀 수 있습니다. 바꾼 뒤 비중을 맞추려면 매매 화면에서 직접 사고팝니다.`}
      </p>
      {alertText !== null && (
        <div ref={alertBox} role="alert" tabIndex={-1} className={styles.alert}>
          <p>{alertText}</p>
        </div>
      )}
      <div className={styles.shareGrid}>
        {SHARE_KEYS.map((key) => (
          <div key={key} className={styles.shareRow}>
            <label htmlFor={`${baseId}-${key}`}>{SHARE_LABELS[key]}</label>
            <span
              className={styles.nowBox}
            >{`지금 ${String(current[key])}`}</span>
            <span aria-hidden="true">→</span>
            <input
              id={`${baseId}-${key}`}
              type="text"
              inputMode="numeric"
              autoComplete="off"
              className={styles.numberInput}
              value={shares[key]}
              disabled={locked}
              aria-invalid={check.invalid.includes(key) ? true : undefined}
              aria-describedby={`${baseId}-sum`}
              onChange={(e) => {
                setShares({ ...shares, [key]: e.target.value });
              }}
            />
          </div>
        ))}
      </div>
      <p
        id={`${baseId}-sum`}
        className={check.problem === null ? styles.sumOk : styles.sumBad}
      >
        {`합계 ${sumText} / 100`}
        {check.problem !== null && (
          <span>{` · ${FORMATION_PROBLEM_TEXT[check.problem]}`}</span>
        )}
      </p>
      {failure?.groups.formation && (
        <p role="alert" className={styles.fieldError}>
          {failure.groups.formation}
        </p>
      )}

      <div className={styles.field}>
        <label htmlFor={`${baseId}-reason`}>사유 (선택, 한 줄)</label>
        <input
          id={`${baseId}-reason`}
          type="text"
          autoComplete="off"
          className={styles.textInput}
          value={reason}
          disabled={locked}
          aria-invalid={!reasonOk || failure?.groups.reason ? true : undefined}
          aria-describedby={`${baseId}-reason-count`}
          onChange={(e) => {
            setReason(e.target.value);
          }}
        />
        <span id={`${baseId}-reason-count`} className={styles.hint}>
          {`${String(count)} / ${String(REASON_MAX)}자 · 복기에서 그대로 보입니다.`}
        </span>
        {failure?.groups.reason && reasonOk && (
          <span role="alert" className={styles.fieldError}>
            {failure.groups.reason}
          </span>
        )}
        {!reasonOk && (
          <span className={styles.fieldError}>{REASON_TOO_LONG_TEXT}</span>
        )}
      </div>

      {same && check.problem === null && (
        <p className={styles.hint}>{SAME_FORMATION_TEXT}</p>
      )}
      <div className={styles.formActions}>
        <Link
          to={`/seasons/${String(season.id)}/trade`}
          className={styles.secondaryLink}
        >
          취소
        </Link>
        <button type="submit" className={styles.primary} disabled={!canSubmit}>
          {change.isPending ? '적용 중…' : '전술 변경 적용'}
        </button>
      </div>
    </form>
  );
}

import { useState, type FormEvent } from 'react';
import { messageFor, UNREACHABLE_TEXT } from '../../api/errorMessages.ts';
import { ProblemError } from '../../api/problem.ts';
import { ratioToPercent } from '../format/ratio.ts';
import {
  checkFormation,
  FORMATION_PROBLEM_TEXT,
  SHARE_KEYS,
  type ShareKey,
  type ShareTexts,
} from './formationCheck.ts';
import type { StartSeasonRequest } from './queries.ts';
import { PATH_LENGTHS, RISK_LIMITS } from './startOptions.ts';
import styles from './Seasons.module.css';

const SHARE_LABELS: Record<ShareKey, string> = {
  goalkeeper: '골키퍼 (현금·안전)',
  defender: '수비수',
  midfielder: '미드필더',
  forward: '공격수',
};

const FIELD_TEXT = {
  riskLimit: '위험 선언을 −10%~−40% 중에서 골라 주세요.',
  formation: '포메이션을 확인해 주세요(합 100, 골키퍼 1 이상).',
  pathLength: '시즌 길이는 12·24·60턴 중 하나입니다.',
} as const;

type FieldGroup = keyof typeof FIELD_TEXT;

function groupOf(field: string): FieldGroup | null {
  if (field === 'riskLimit') return 'riskLimit';
  if (field === 'formation' || field.startsWith('formation.')) {
    return 'formation';
  }
  if (field === 'pathLength') return 'pathLength';
  return null;
}

interface Failure {
  alert: string | null;
  groups: Partial<Record<FieldGroup, string>>;
}

function failureOf(error: unknown): Failure {
  if (!(error instanceof ProblemError)) {
    return { alert: UNREACHABLE_TEXT, groups: {} };
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

export function SeasonForm({
  pending,
  error,
  onSubmit,
}: {
  pending: boolean;
  error: unknown;
  onSubmit: (body: StartSeasonRequest) => void;
}) {
  // No risk limit is preselected: it is a self-promise the player must make.
  const [risk, setRisk] = useState<string | null>(null);
  const [shares, setShares] = useState<ShareTexts>({
    goalkeeper: '25',
    defender: '25',
    midfielder: '25',
    forward: '25',
  });
  const [length, setLength] = useState<number>(12);

  // Derived on every render, not stored.
  const check = checkFormation(shares);
  const canSubmit = risk !== null && check.problem === null && !pending;
  const failure = error === null ? null : failureOf(error);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!canSubmit || check.formation === null) return;
    onSubmit({
      riskLimit: risk,
      formation: check.formation,
      pathLength: length,
    });
  }

  const sumOnly = risk !== null && check.problem === 'sum';
  const sumText = check.sum === null ? '–' : String(check.sum);

  return (
    <form className={styles.form} onSubmit={submit} noValidate>
      {failure?.alert && (
        <div role="alert" className={styles.alert}>
          <p>{failure.alert}</p>
        </div>
      )}

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>위험 선언 (감수할 최대 낙폭)</legend>
        <div className={`${styles.choices} ${styles.four}`}>
          {RISK_LIMITS.map((value) => (
            <label key={value} className={styles.choice}>
              <input
                type="radio"
                name="riskLimit"
                value={value}
                checked={risk === value}
                onChange={() => {
                  setRisk(value);
                }}
              />
              <span>{ratioToPercent(value)}</span>
            </label>
          ))}
        </div>
        {failure?.groups.riskLimit && (
          <p className={styles.fieldError}>{failure.groups.riskLimit}</p>
        )}
        <p className={styles.hint}>
          낙폭이 선언을 넘어도 시즌은 계속됩니다. 성적표에 기록됩니다.
        </p>
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>포메이션 (정수 %, 합 100)</legend>
        <div className={styles.shares}>
          {SHARE_KEYS.map((key) => (
            <label key={key} className={styles.share}>
              <span>{SHARE_LABELS[key]}</span>
              <input
                type="text"
                inputMode="numeric"
                autoComplete="off"
                className={styles.numberInput}
                value={shares[key]}
                aria-invalid={check.invalid.includes(key) ? true : undefined}
                aria-describedby="formation-sum"
                onChange={(e) => {
                  setShares({ ...shares, [key]: e.target.value });
                }}
              />
            </label>
          ))}
        </div>
        <p
          id="formation-sum"
          className={check.problem === null ? styles.sumOk : styles.sumBad}
        >
          {`합계 ${sumText} / 100`}
          {check.problem !== null && (
            <span>{` · ${FORMATION_PROBLEM_TEXT[check.problem]}`}</span>
          )}
        </p>
        {failure?.groups.formation && (
          <p className={styles.fieldError}>{failure.groups.formation}</p>
        )}
        <p className={styles.hint}>골키퍼는 1 이상이어야 합니다.</p>
      </fieldset>

      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>시즌 길이</legend>
        <div className={`${styles.choices} ${styles.three}`}>
          {PATH_LENGTHS.map((value) => (
            <label key={value} className={styles.choice}>
              <input
                type="radio"
                name="pathLength"
                value={String(value)}
                checked={length === value}
                onChange={() => {
                  setLength(value);
                }}
              />
              <span>{`${String(value)}턴`}</span>
            </label>
          ))}
        </div>
        {failure?.groups.pathLength && (
          <p className={styles.fieldError}>{failure.groups.pathLength}</p>
        )}
        <p className={styles.hint}>
          초기 자금과 수수료율은 서버가 정합니다. 시즌이 끝나기 전에는 실제
          날짜가 보이지 않습니다.
        </p>
      </fieldset>

      {risk === null && (
        <p className={styles.hint}>위험 선언을 하나 골라 주세요.</p>
      )}
      <button type="submit" className={styles.primary} disabled={!canSubmit}>
        {sumOnly ? '시즌 시작 (합 100이 되면 켜짐)' : '시즌 시작'}
      </button>
    </form>
  );
}

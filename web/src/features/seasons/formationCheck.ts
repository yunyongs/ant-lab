import { parseWholeNumber } from '../format/integer.ts';
import type { Formation } from './labels.ts';

export const SHARE_KEYS = [
  'goalkeeper',
  'defender',
  'midfielder',
  'forward',
] as const;
export type ShareKey = (typeof SHARE_KEYS)[number];
export type ShareTexts = Record<ShareKey, string>;

export type FormationProblem = 'whole' | 'goalkeeper' | 'sum';

export const FORMATION_PROBLEM_TEXT: Record<FormationProblem, string> = {
  whole: '0–100 사이의 정수로 적어 주세요',
  goalkeeper: '골키퍼는 1 이상이어야 합니다',
  sum: '합계가 100이 되어야 합니다',
};

export interface FormationCheck {
  formation: Formation | null;
  // Sum of the typed shares; null while any of them is not a whole number.
  sum: number | null;
  problem: FormationProblem | null;
  invalid: ShareKey[];
}

// Checks only what the player typed (integers); the server repeats the rules.
export function checkFormation(texts: ShareTexts): FormationCheck {
  const parsed = SHARE_KEYS.map((key) => [
    key,
    parseWholeNumber(texts[key], 100),
  ]);
  const bad = parsed.flatMap(([key, value]) =>
    value === null ? [key as ShareKey] : [],
  );
  if (bad.length > 0) {
    return { formation: null, sum: null, problem: 'whole', invalid: bad };
  }
  const formation: Formation = {
    goalkeeper: parseWholeNumber(texts.goalkeeper, 100) ?? 0,
    defender: parseWholeNumber(texts.defender, 100) ?? 0,
    midfielder: parseWholeNumber(texts.midfielder, 100) ?? 0,
    forward: parseWholeNumber(texts.forward, 100) ?? 0,
  };
  const sum =
    formation.goalkeeper +
    formation.defender +
    formation.midfielder +
    formation.forward;
  if (formation.goalkeeper < 1) {
    return { formation, sum, problem: 'goalkeeper', invalid: ['goalkeeper'] };
  }
  if (sum !== 100) {
    return { formation, sum, problem: 'sum', invalid: [...SHARE_KEYS] };
  }
  return { formation, sum, problem: null, invalid: [] };
}

// True when both formations hold the same four integers.
export function sameFormation(a: Formation, b: Formation): boolean {
  return SHARE_KEYS.every((key) => a[key] === b[key]);
}

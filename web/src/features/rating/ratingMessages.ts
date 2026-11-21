import { formatDecimal } from '../format/decimal.ts';

export const RATING_TITLE = '매니저 레이팅';
export const RATING_LOADING_TEXT = '레이팅을 불러오는 중입니다…';
export const RATING_ERROR_TEXT = '레이팅을 지금 불러오지 못했습니다.';
export const RATING_RETRY_LABEL = '다시 불러오기';
export const RATING_NONE_TEXT = '레이팅이 아직 없습니다.';
export const HISTORY_TITLE = '레이팅 이력 (최근 시즌부터)';
export const NO_RATING_MARK = '—';

export const ratingValueText = (rating: string) => `${formatDecimal(rating)}점`;
export const countedText = (counted: number) =>
  `집계 시즌 ${String(counted)}개`;
export const missingText = (missing: number, counted: number) =>
  `집계 시즌이 3개가 되면 레이팅이 보입니다. ${String(missing)}개 더 필요합니다(지금 ${String(counted)}개).`;

export const notStartedText = (n: number) =>
  `3턴 전에 포기해 뺀 시즌 ${String(n)}개`;
export const noScoreText = (n: number) =>
  `경로 분포가 없어 뺀 시즌 ${String(n)}개`;
export const pendingText = (n: number) =>
  `엔진이 답하지 못해 아직 점수가 없는 시즌 ${String(n)}개`;

export const historyRowText = (
  seasonId: number,
  score: string,
  rating: string | null,
) =>
  `시즌 ${String(seasonId)} · ${formatDecimal(score)}점 · 레이팅 ${rating === null ? NO_RATING_MARK : formatDecimal(rating)}`;

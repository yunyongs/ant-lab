export const SCORE_TITLE = '시즌 점수';
export const SCORE_LOADING_TEXT =
  '점수를 계산하는 중입니다… 처음 열 때는 몇 초 걸릴 수 있습니다.';
export const ENGINE_BUSY_TEXT =
  '점수를 계산하는 서비스가 지금 바쁘거나 연결되지 않습니다. 성적표의 나머지는 그대로 볼 수 있습니다.';
export const SCORE_RETRY_LABEL = '다시 시도';

export const NO_SCORE_TEXT = '점수 없음';
export const NO_VALUE_TEXT = '아직 없음';
export const NO_DISTRIBUTION_TEXT = '해당 없음 (경로 분포가 없습니다)';

export const COUNTED_TEXT = '레이팅에 집계됩니다.';
export const NOT_COUNTED_TEXT = '레이팅에 집계되지 않습니다.';

export const LUCK_NOTE = '보여 주기만 하고 점수에는 넣지 않습니다.';
export const PROCESS_NOTE =
  '값이 있는 항목의 평균입니다. 값이 없는 항목은 아직 없음으로 보입니다.';

export const pointsText = (value: string) => `${value}점`;
export const itemsTitle = (processItems: number) =>
  `과정 항목 (값이 있는 ${String(processItems)}개의 평균)`;
export const versionText = (version: string) => `공식 ${version} · 초안`;
export const NOT_READY_TEXT = '점수를 아직 볼 수 없습니다.';

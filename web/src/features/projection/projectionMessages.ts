import { groupDigits } from '../format/integer.ts';

// The caption is fixed text: only the path count varies. The sample years the
// product text names are not served by the API yet, so they are left out.
export function captionText(pathCount: number | null): string {
  const runs = pathCount === null ? '여러' : groupDigits(pathCount);
  return `과거의 실제 달들을 재조합해 ${runs}번 돌린 결과의 범위입니다. 예측이 아닙니다.`;
}

export const SURVIVORSHIP_TEXT =
  '지금 상장된 종목만 쓰기 때문에 과거 결과는 실제보다 좋게 보일 수 있습니다.';

export const PROJECTION_LOADING_TEXT =
  '예상 분포를 계산하는 중입니다… 잠시 걸릴 수 있습니다.';

export const ENGINE_BUSY_TEXT =
  '계산 서비스가 지금 바쁘거나 연결되지 않습니다. 잠시 뒤 다시 시도해 주세요.';

export const HISTORY_TOO_SHORT_TEXT =
  '이 팀의 예상 분포를 계산하기에는 과거 이력이 짧습니다.';

export const CHART_TITLE =
  '예상 분포 띠 그림: 시간이 지나며 팀 가치가 퍼질 수 있는 범위';

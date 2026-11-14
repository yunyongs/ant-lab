export const REPORT_LOADING_TEXT = '성적표를 불러오는 중입니다…';
export const NOT_APPLICABLE = '해당 없음';

export const NULL_REASON = {
  monthlyVolatility: '한 턴뿐이라 계산하지 않습니다',
  roleFit: '실측된 선수가 없습니다',
  nudge: '매매 대금이 0입니다',
} as const;

export const BENCHMARK_NOTE =
  '기준선: 국내·미국 시장 지수 대용 ETF를 반씩 사서 끝까지 보유한 값입니다. 가격 수익률과 수정 종가가 섞여 있어 치우침이 있습니다.';

export const RECON_HINT =
  '턴을 넘길 때마다 계좌를 맞춰 본 기록입니다. 불일치가 있었던 시도는 되돌려졌습니다.';

export const LIMITS_TITLE = '이 성적표의 한계';
export const LIMITS = [
  '국내 종목은 배당을 뺀 가격 수익률입니다(연 1–2%p 낮게 나옵니다).',
  '국내 주식 매도 거래세와 세금은 아직 반영하지 않습니다.',
  '수수료에는 미국 종목의 환전 비용이 들어 있습니다(따로 나누어 보이지 않습니다).',
  '시즌 점수·레이팅, 연평균 성장률, Sharpe, 경로 백분위는 다음 단계에서 더합니다.',
] as const;

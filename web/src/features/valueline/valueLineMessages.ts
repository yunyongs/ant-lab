export const VALUE_LOADING = '총 평가액 흐름을 불러오는 중입니다…';
export const VALUE_TOO_FEW =
  '턴을 하나 넘기면 총 평가액 그래프가 그려집니다. 지금은 표 탭만 봐 주세요.';
export const TABLE_CAPTION = '턴별 총 평가액 (그래프와 같은 숫자)';
export const DRAWDOWN_ALL_ZERO = '누적 낙폭은 모든 턴 0.00%';
export const CHART_FAILED =
  '그래프를 불러오지 못했습니다. 같은 숫자는 표 탭에 있습니다.';

export const CHART_UNDRAWABLE =
  '그래프로 그릴 수 없습니다 — 표 탭에서 확인하세요.';

export const chartLabel = (lastTurn: number, withBenchmark: boolean) =>
  `0턴부터 ${String(lastTurn)}턴까지 ${withBenchmark ? '총 평가액과 시장 기준선' : '총 평가액'} 선 그래프. 같은 숫자는 표 탭에 있습니다.`;

export const guideTitle = (limit: string) => `위험 선언 ${limit}`;

export const LEGEND_LINE =
  '실선: 턴마다 총 평가액. 0턴(시작 자금)보다 높으면 상승 색, 낮으면 하락 색입니다.';
export const legendGuide = (limit: string) =>
  `빨간 가로 점선: 위험 선언 ${limit}. 지금까지 가장 높았던 총 평가액에서 그만큼 내려간 높이입니다.`;
export const LEGEND_BENCHMARK =
  '회색 점선: 시장 기준선. 같은 시작 자금으로 시장 지수를 0턴에 사서 그대로 둔 경우의 평가액입니다.';
export const LEGEND_AXIS = '가로: 턴 번호';

// The licence of the chart library asks for this notice and a link to its maker.
export const ATTRIBUTION_TEXT =
  '그래프: TradingView Lightweight Charts™ Copyright (c) 2025 TradingView, Inc.';
export const ATTRIBUTION_URL = 'https://www.tradingview.com/';

export const VALUE_TITLE = '총 평가액 흐름';
export const VALUE_LOADING = '총 평가액 흐름을 불러오는 중입니다…';
export const VALUE_TOO_FEW =
  '턴을 하나 넘기면 총 평가액 그래프가 그려집니다. 지금은 아래 표만 봐 주세요.';
export const TABLE_CAPTION = '턴별 총 평가액 (그래프와 같은 숫자)';
export const CHART_FAILED =
  '그래프를 불러오지 못했습니다. 같은 숫자는 아래 표에 있습니다.';

export const chartLabel = (lastTurn: number) =>
  `0턴부터 ${String(lastTurn)}턴까지 총 평가액 선 그래프. 같은 숫자는 아래 표에 있습니다.`;

export const guideTitle = (limit: string) => `위험 선언 ${limit}`;

export const LEGEND_LINE =
  '실선: 턴마다 총 평가액. 0턴(시작 자금)보다 높으면 상승 색, 낮으면 하락 색입니다.';
export const legendGuide = (limit: string) =>
  `점선: 위험 선언 ${limit}. 지금까지 가장 높았던 총 평가액에서 그만큼 내려간 높이입니다.`;
export const LEGEND_AXIS = '가로: 턴 번호';

// The licence of the chart library asks for this notice and a link to its maker.
export const ATTRIBUTION_TEXT =
  '그래프: TradingView Lightweight Charts™ Copyright (c) 2025 TradingView, Inc.';
export const ATTRIBUTION_URL = 'https://www.tradingview.com/';

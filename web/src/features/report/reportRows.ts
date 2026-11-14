import { formatWon } from '../format/money.ts';
import { ratioWithPercent, ratioWithPoints } from '../format/ratio.ts';
import { NOT_APPLICABLE, NULL_REASON } from './reportMessages.ts';
import type { Report } from './queries.ts';

// Every field of the report has a Korean label and one presentation here; the
// DTO is never spread into the page. Values are the server's strings.
export interface RowDef {
  label: string;
  hint?: string;
  keys: (keyof Report)[];
  value: (r: Report) => string;
  big?: boolean;
}

export interface GroupDef {
  id: string;
  title: string;
  rows: RowDef[];
  // Folded groups are closed `<details>` with this summary value.
  summary?: (r: Report) => string;
}

const count = (n: number, unit: string) => `${String(n)}${unit}`;

// A null cell says so, with the fixed reason from the contract.
function orNone(
  value: string | null,
  reason: string,
  format: (v: string) => string,
): string {
  return value === null ? `${NOT_APPLICABLE} (${reason})` : format(value);
}

export const META_KEYS: (keyof Report)[] = [
  'seasonStatus',
  'isFinal',
  'reportTurns',
  'pathLength',
  'pendingOrders',
  'pendingChanges',
];

export const GROUPS: GroupDef[] = [
  {
    id: 'result',
    title: '결과',
    rows: [
      {
        label: '총 수익률',
        keys: ['totalReturn'],
        value: (r) => ratioWithPercent(r.totalReturn),
        big: true,
      },
      {
        label: '초기 자금',
        keys: ['initialCash'],
        value: (r) => formatWon(r.initialCash),
      },
      {
        label: '최종 평가액',
        keys: ['finalValue'],
        value: (r) => formatWon(r.finalValue),
      },
      {
        label: '기준선 최종 평가액',
        keys: ['baselineFinalValue'],
        value: (r) => formatWon(r.baselineFinalValue),
      },
      {
        label: '기준선 수익률',
        keys: ['baselineReturn'],
        value: (r) => ratioWithPercent(r.baselineReturn),
      },
      {
        label: '초과 수익률',
        hint: '(최종 − 기준선 최종) ÷ 초기 자금',
        keys: ['excessReturn'],
        value: (r) => ratioWithPercent(r.excessReturn),
      },
      {
        label: '월 변동성',
        keys: ['monthlyVolatility'],
        value: (r) =>
          orNone(
            r.monthlyVolatility,
            NULL_REASON.monthlyVolatility,
            ratioWithPercent,
          ),
      },
      {
        label: '최대 낙폭',
        keys: ['maxDrawdown'],
        value: (r) => ratioWithPercent(r.maxDrawdown),
      },
    ],
  },
  {
    id: 'risk',
    title: '위험 준수',
    rows: [
      {
        label: '위험 선언',
        keys: ['riskLimit'],
        value: (r) => ratioWithPercent(r.riskLimit),
      },
      {
        label: '선언을 넘은 턴',
        keys: ['riskBreachTurns'],
        value: (r) => count(r.riskBreachTurns, '턴'),
      },
      {
        label: '가장 깊이 넘은 정도',
        keys: ['riskBreachDepth'],
        value: (r) => ratioWithPoints(r.riskBreachDepth),
      },
      {
        label: '최대 단일 종목 비중',
        keys: ['maxSingleWeight'],
        value: (r) => ratioWithPercent(r.maxSingleWeight),
      },
    ],
  },
  {
    id: 'process',
    title: '과정',
    rows: [
      {
        label: '연 환산 회전율',
        keys: ['turnoverAnnual'],
        value: (r) => ratioWithPercent(r.turnoverAnnual),
      },
      {
        label: '포메이션 이탈 평균',
        keys: ['formationDeviation'],
        value: (r) => ratioWithPoints(r.formationDeviation),
      },
      {
        label: '역할 수행',
        keys: ['roleFit', 'rolePlayersJudged', 'rolePlayersUnjudged'],
        value: (r) =>
          `${orNone(r.roleFit, NULL_REASON.roleFit, ratioWithPercent)} (판정 ${String(r.rolePlayersJudged)} · 미판정 ${String(r.rolePlayersUnjudged)})`,
      },
      {
        label: '독려 비중',
        keys: ['nudgeShare'],
        value: (r) => orNone(r.nudgeShare, NULL_REASON.nudge, ratioWithPercent),
      },
      {
        label: '같은 턴 독려 비중',
        keys: ['nudgeSameTurnShare'],
        value: (r) =>
          orNone(r.nudgeSameTurnShare, NULL_REASON.nudge, ratioWithPercent),
      },
      {
        label: '전술 변경',
        keys: [
          'formationChanges',
          'formationChangeOrders',
          'changesWithoutOrders',
        ],
        value: (r) =>
          `${count(r.formationChanges, '회')} (연결 주문 ${count(r.formationChangeOrders, '건')} · 주문 없는 변경 ${count(r.changesWithoutOrders, '회')})`,
      },
    ],
  },
  {
    id: 'cost',
    title: '비용',
    summary: (r) => `총 수수료 ${formatWon(r.totalFee)}`,
    rows: [
      {
        label: '매매 대금 합 (0턴 제외)',
        keys: ['tradeNotional'],
        value: (r) => formatWon(r.tradeNotional),
      },
      {
        label: '총 수수료 (0턴 포함)',
        keys: ['totalFee'],
        value: (r) => formatWon(r.totalFee),
      },
      {
        label: '0턴 팀 구성 수수료',
        keys: ['teamFee'],
        value: (r) => formatWon(r.teamFee),
      },
      {
        label: '전술 변경에 묶인 수수료',
        keys: ['formationChangeFee'],
        value: (r) => formatWon(r.formationChangeFee),
      },
      {
        label: '플랫폼 독려 몫 수수료',
        keys: ['platformNudgeFee'],
        value: (r) => formatWon(r.platformNudgeFee),
      },
    ],
  },
  {
    id: 'recon',
    title: '대사',
    summary: (r) => `불일치 ${count(r.reconMismatchRows, '행')}`,
    rows: [
      {
        label: '기록된 불일치 행 (복구된 시도 포함)',
        keys: ['reconMismatchRows'],
        value: (r) => count(r.reconMismatchRows, '행'),
      },
      {
        label: '턴 넘기기 검산 묶음',
        keys: ['reconTurnBundles'],
        value: (r) => count(r.reconTurnBundles, '묶음'),
      },
      {
        label: '수동 검산 묶음',
        keys: ['reconManualBundles'],
        value: (r) => count(r.reconManualBundles, '묶음'),
      },
      {
        label: '경로 밖 턴의 검산 행',
        keys: ['reconOutOfRangeRows'],
        value: (r) => count(r.reconOutOfRangeRows, '행'),
      },
    ],
  },
];

// Every key the screen shows, for the "all 37 fields" check.
export const SHOWN_KEYS: (keyof Report)[] = [
  ...META_KEYS,
  ...GROUPS.flatMap((g) => g.rows.flatMap((row) => row.keys)),
];

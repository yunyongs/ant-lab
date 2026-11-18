import type { components } from '../../api/schema';

type SeasonAnalysisDto = components['schemas']['SeasonAnalysisDto'];
type SourceFeeDto = components['schemas']['SourceFeeDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented
// and all distinct so a wrong field shows up as a wrong string.
export function sourceRows(): SourceFeeDto[] {
  return [
    { source: 'RANKING', orders: 3, tradeNotional: '3100000', fee: '4100' },
    { source: 'FEED', orders: 5, tradeNotional: '5200000', fee: '5200' },
    { source: 'ALERT', orders: 2, tradeNotional: '2300000', fee: '2300' },
    { source: 'BENCH', orders: 4, tradeNotional: '4400000', fee: '4400' },
    { source: 'DIRECT', orders: 7, tradeNotional: '7500000', fee: '7500' },
  ];
}

export function analysisFixture(
  overrides: Partial<SeasonAnalysisDto> = {},
): SeasonAnalysisDto {
  return {
    analysisVersion: 'analysis-9.9',
    representativeVersion: 'rep-9.9',
    reportTurns: 12,
    baselines: [],
    decomposition: null,
    discipline: {
      dropThreshold: '-0.05000000',
      riseThreshold: '0.05000000',
      sellNotional: '0',
      panicSellNotional: '0',
      panicSellShare: null,
      buyNotional: '0',
      chaseBuyNotional: '0',
      chaseBuyShare: null,
      tradeNotional: '0',
      undisciplinedShare: null,
      formationChanges: 0,
      panicFormationChanges: 0,
    },
    noise: {
      noiseShare: '0.31250000',
      tradingTurns: 9,
      followedBuyNotional: '1234000',
      followedSellNotional: '567000',
      tradeNotional: '21500000',
    },
    platform: {
      totalFee: '30101',
      teamFee: '6701',
      bySource: sourceRows(),
      nudgedFee: '6400',
      nudgedFeeShare: '0.21262000',
    },
    attentionFollowing: {
      windowTurns: 6,
      attentionBuys: 4,
      attentionBuyNotional: '8100000',
      attentionReturn: '0.04500000',
      otherBuys: 11,
      otherBuyNotional: '9200000',
      otherReturn: '0.01750000',
      incompleteBuys: 2,
    },
    defenderGoals: {
      downMarketTurns: 5,
      goals: 8,
      goalWon: '777000',
      goalReturn: '0.00777000',
    },
    changeEffects: [
      {
        turnNo: 3,
        windowTurns: 6,
        effectWon: '151000',
        effectReturn: '0.00151000',
      },
      {
        turnNo: 8,
        windowTurns: 4,
        effectWon: '-262000',
        effectReturn: '-0.00262000',
      },
    ],
    ...overrides,
  };
}

// Strings that belong to the unrendered MVP-17 blocks or to no field at all.
export const poisonedAnalysisStrings = [
  '2001-01',
  '-8123456789012345678',
  'Invented asset name',
  'EXTRA-NOISE-KEY',
  '9876543',
];

export function poisonedAnalysis(): SeasonAnalysisDto {
  const base = analysisFixture();
  return {
    ...base,
    baselines: [
      {
        key: '2001-01',
        available: true,
        reason: 'Invented asset name',
        legs: [{ asset: 'Invented asset name', slot: 'x', sharePercent: 50 }],
        finalValue: '9876543',
        totalReturn: null,
        excessWon: null,
        excessReturn: null,
        totalFee: null,
        rebalanceCount: null,
        bandNoFillCount: null,
        forcedChangeCount: null,
        forcedChangeFee: null,
        bondLegSubstituted: false,
        turnValues: ['-8123456789012345678'],
      },
    ],
    noise: {
      ...base.noise,
      ...({ 'EXTRA-NOISE-KEY': '9876543' } as Record<string, string>),
    },
  };
}

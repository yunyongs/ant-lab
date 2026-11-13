import { describe, expect, it } from 'vitest';
import { canChangeFormation, canTrade } from './seasonGate.ts';

describe('seasonGate', () => {
  it.each([
    ['IN_PROGRESS', 0, true, false],
    ['IN_PROGRESS', 3, true, true],
    ['FINISHED', 12, false, false],
    ['ABANDONING', 3, false, false],
  ] as const)('%s at turn %i', (status, currentTurnNo, trade, formation) => {
    expect(canTrade({ status })).toBe(trade);
    expect(canChangeFormation({ status, currentTurnNo })).toBe(formation);
  });
});

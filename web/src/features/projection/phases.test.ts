import { describe, expect, it } from 'vitest';
import { phasesFor } from './phases.ts';

describe('phasesFor', () => {
  it.each([
    ['IN_PROGRESS', 0, ['CURRENT']],
    ['IN_PROGRESS', 4, ['CURRENT', 'START']],
    ['ABANDONING', 5, ['CURRENT', 'START']],
    ['HALTED', 5, ['CURRENT', 'START']],
    ['ABANDONING', 0, ['CURRENT']],
    ['FINISHED', 12, ['END', 'START']],
  ] as const)('%s at turn %i', (status, currentTurnNo, expected) => {
    expect(phasesFor({ status, currentTurnNo })).toEqual(expected);
  });

  it('never offers CURRENT after the finish nor END before it', () => {
    expect(phasesFor({ status: 'FINISHED', currentTurnNo: 12 })).not.toContain(
      'CURRENT',
    );
    for (const status of ['IN_PROGRESS', 'ABANDONING', 'HALTED'] as const) {
      expect(phasesFor({ status, currentTurnNo: 3 })).not.toContain('END');
    }
  });
});

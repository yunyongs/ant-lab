import { describe, expect, it } from 'vitest';
import { holdingsFixture } from '../../test/fixtures/holdings.ts';
import { buyHint } from './teamHint.ts';

describe('buyHint', () => {
  it('names one amount when every field position has the same gap', () => {
    const holdings = holdingsFixture({
      currentFormation: {
        goalkeeper: 25,
        defender: 25,
        midfielder: 25,
        forward: 25,
      },
    });
    expect(buyHint(holdings)).toBe(
      '수비수·미드필더·공격수에 각각 약 2,500만 원씩 사면 목표와 맞습니다.',
    );
  });

  it('lists each gap when they differ and leaves out met positions', () => {
    const holdings = holdingsFixture({
      defenderValue: '30000000',
      goalkeeperValue: '70000000',
    });
    expect(buyHint(holdings)).toBe(
      '미드필더 약 2,000만 원, 공격수 약 1,000만 원을 사면 목표와 맞습니다.',
    );
  });

  it('is hidden when nothing is below target or the value is malformed', () => {
    expect(
      buyHint(
        holdingsFixture({
          defenderValue: '30000000',
          midfielderValue: '20000000',
          forwardValue: '10000000',
        }),
      ),
    ).toBeNull();
    expect(buyHint(holdingsFixture({ totalValue: 'x' }))).toBeNull();
  });

  it('skips a gap below one man won', () => {
    expect(
      buyHint(
        holdingsFixture({
          totalValue: '9999',
          defenderValue: '0',
          midfielderValue: '0',
          forwardValue: '0',
        }),
      ),
    ).toBeNull();
  });

  // Field-position gap with a 100,000,000 total and a 30% target is
  // 30,000,000 minus the held value; the amount floors to whole 만 원.
  const defenderGap = (held: string) =>
    buyHint(
      holdingsFixture({
        defenderValue: held,
        midfielderValue: '20000000',
        forwardValue: '10000000',
      }),
    );

  it('hides a gap of 9999.99 won and shows 1만 for exactly 10000', () => {
    expect(defenderGap('29990000.01')).toBeNull();
    expect(defenderGap('29990000')).toBe(
      '수비수에 각각 약 1만 원씩 사면 목표와 맞습니다.',
    );
  });

  it('floors 19999.99 won down to 1만, never rounds up to 2만', () => {
    expect(defenderGap('29980000.01')).toBe(
      '수비수에 각각 약 1만 원씩 사면 목표와 맞습니다.',
    );
  });

  it('leaves out positions that are over their target', () => {
    expect(
      buyHint(
        holdingsFixture({
          defenderValue: '45000000',
          midfielderValue: '10000000',
          forwardValue: '10000000',
        }),
      ),
    ).toBe('미드필더에 각각 약 1,000만 원씩 사면 목표와 맞습니다.');
  });
});

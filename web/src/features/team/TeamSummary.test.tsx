import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { holdingsFixture } from '../../test/fixtures/holdings.ts';
import { TeamSummary } from './TeamSummary.tsx';

describe('TeamSummary bars', () => {
  it('gives a 2% forward share its own width and adds up to 100%', () => {
    render(
      <TeamSummary
        holdings={holdingsFixture({
          goalkeeperWeight: '0.50000000',
          defenderWeight: '0.30000000',
          midfielderWeight: '0.18000000',
          forwardWeight: '0.02000000',
        })}
      />,
    );
    const summary = screen.getByRole('region', { name: '팀 요약' });
    const keys = ['goalkeeper', 'defender', 'midfielder', 'forward'];
    const widths = keys.map(
      (key) => within(summary).getByTestId(`fill-${key}`).style.width,
    );
    expect(widths).toEqual(['50%', '30%', '18%', '2%']);
  });
});

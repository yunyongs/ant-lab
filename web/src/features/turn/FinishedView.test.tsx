import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { expectNoDatePattern } from '../../test/expectNoDatePattern.ts';
import { renderRoute } from '../../test/renderWithProviders.tsx';
import { serveTurnWorld } from '../../test/serveTurns.ts';

describe('FinishedView', () => {
  it('shows the status box, the last result, the report and review links', async () => {
    const world = serveTurnWorld({ current: 12 });
    world.status = 'FINISHED';
    const { container } = renderRoute('/seasons/7');
    const box = (await screen.findByText('시즌이 끝났습니다')).closest(
      '[role="status"]',
    );
    expect(box).not.toBeNull();
    expect(
      screen.getByText(/실제 연월과 사건은 복기에서 공개됩니다/),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { name: '12턴 결과 (마지막)' }),
    ).toBeInTheDocument();
    await screen.findByText('104,820,000원');
    expect(screen.getByRole('link', { name: '성적표 보기' })).toHaveAttribute(
      'href',
      '/seasons/7/report',
    );
    expect(screen.getByRole('link', { name: '복기 보기' })).toHaveAttribute(
      'href',
      '/seasons/7/review',
    );
    expect(screen.queryByRole('button', { name: /턴/ })).toBeNull();
    expect(world.turnGets).toEqual([12]);
    expectNoDatePattern(container);
  });
});

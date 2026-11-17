import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { poolFixture } from '../../test/fixtures/pool.ts';
import { rankingFixture } from '../../test/fixtures/ranking.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import { RankingTabs } from './RankingTabs.tsx';
import { rankingRows } from './rankingRows.ts';

function renderTabs() {
  return renderWithProviders(
    <RankingTabs lists={rankingRows(rankingFixture(), poolFixture.assets)} />,
  );
}

describe('RankingTabs', () => {
  it('has a labelled tablist with four tabs and one selected', () => {
    renderTabs();
    const list = screen.getByRole('tablist', { name: '순위 종류' });
    const tabs = within(list).getAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual([
      '등락',
      '관심',
      '연속',
      '그룹',
    ]);
    expect(
      tabs.filter((t) => t.getAttribute('aria-selected') === 'true'),
    ).toHaveLength(1);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    // Roving tabindex: only the selected tab is in the tab order.
    expect(tabs.map((t) => t.getAttribute('tabindex'))).toEqual([
      '0',
      '-1',
      '-1',
      '-1',
    ]);
  });

  it('labels the one panel by its tab and renders no inactive panel', () => {
    renderTabs();
    const panel = screen.getByRole('tabpanel');
    const tab = screen.getByRole('tab', { name: '등락' });
    expect(panel).toHaveAttribute('aria-labelledby', tab.id);
    expect(tab).toHaveAttribute('aria-controls', panel.id);
    expect(screen.getAllByRole('tabpanel')).toHaveLength(1);
    expect(within(panel).getByText('오른 종목')).toBeInTheDocument();
    expect(screen.queryByText('관심이 큰 종목')).toBeNull();
  });

  it('moves and selects with the arrow keys, Home and End, wrapping around', async () => {
    renderTabs();
    const user = userEvent.setup();
    screen.getByRole('tab', { name: '등락' }).focus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: '관심' })).toHaveFocus();
    expect(screen.getByRole('tab', { name: '관심' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.getByRole('tabpanel')).toHaveAttribute(
      'aria-labelledby',
      screen.getByRole('tab', { name: '관심' }).id,
    );
    expect(screen.getByText('관심이 큰 종목')).toBeInTheDocument();
    await user.keyboard('{End}');
    expect(screen.getByRole('tab', { name: '그룹' })).toHaveFocus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: '등락' })).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(screen.getByRole('tab', { name: '그룹' })).toHaveFocus();
    await user.keyboard('{Home}');
    expect(screen.getByRole('tab', { name: '등락' })).toHaveFocus();
    expect(screen.getByRole('tab', { name: '등락' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });

  it('selects a tab on click', async () => {
    renderTabs();
    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: '연속' }));
    expect(screen.getByText('연속으로 오른 종목')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '연속' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.getByRole('tab', { name: '등락' })).toHaveAttribute(
      'aria-selected',
      'false',
    );
  });
});

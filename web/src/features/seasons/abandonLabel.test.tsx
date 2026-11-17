import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import { SeasonCard } from './SeasonCard.tsx';
import { SeasonSummary } from './SeasonSummary.tsx';

const abandoned = seasonFixture({
  status: 'FINISHED',
  currentTurnNo: 12,
  abandonedTurnNo: 5,
});
const earlyAbandoned = seasonFixture({
  status: 'FINISHED',
  currentTurnNo: 12,
  abandonedTurnNo: 1,
  notStarted: true,
});

describe('abandoned label on the season list card (A10)', () => {
  it('shows the turn of the abandon', () => {
    renderWithProviders(
      <ul>
        <SeasonCard season={abandoned} />
      </ul>,
    );
    expect(screen.getByText('5턴에서 포기')).toBeInTheDocument();
  });

  it('shows the not-started mark when the server says so', () => {
    renderWithProviders(
      <ul>
        <SeasonCard season={earlyAbandoned} />
      </ul>,
    );
    expect(screen.getByText('1턴에서 포기 · 미시작')).toBeInTheDocument();
  });

  it('shows nothing for a season that was played to the end', () => {
    renderWithProviders(
      <ul>
        <SeasonCard season={seasonFixture({ status: 'FINISHED' })} />
      </ul>,
    );
    expect(screen.queryByText(/포기/)).toBeNull();
  });
});

describe('abandoned label on the season summary', () => {
  it('shows the label of an abandoned season, also while it is being wound up', () => {
    renderWithProviders(
      <SeasonSummary
        season={seasonFixture({ status: 'ABANDONING', abandonedTurnNo: 4 })}
      />,
    );
    expect(screen.getByText('4턴에서 포기')).toBeInTheDocument();
  });

  it('shows the not-started mark and none for a normal season', () => {
    const { unmount } = renderWithProviders(
      <SeasonSummary season={earlyAbandoned} />,
    );
    expect(screen.getByText('1턴에서 포기 · 미시작')).toBeInTheDocument();
    unmount();
    renderWithProviders(<SeasonSummary season={seasonFixture()} />);
    expect(screen.queryByText(/포기/)).toBeNull();
    expect(screen.queryByText(/미시작/)).toBeNull();
  });
});

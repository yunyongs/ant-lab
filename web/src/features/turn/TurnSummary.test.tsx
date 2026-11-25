import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../../test/authHandlers.ts';
import { expectNoDatePattern } from '../../test/expectNoDatePattern.ts';
import { contributionFixture } from '../../test/fixtures/contribution.ts';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { turnBreached, turnResultFixture } from '../../test/fixtures/turns.ts';
import { seasonKey } from '../seasons/queries.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import { server } from '../../test/server.ts';
import { TurnSummary } from './TurnSummary.tsx';

// Answers only the requested turn; any other turn number fails the request.
function serveContribution(turnNo: number) {
  server.use(
    http.get('*/api/v1/seasons/7/turns/:turnNo/contribution', ({ params }) =>
      params.turnNo === String(turnNo)
        ? HttpResponse.json(contributionFixture({ turnNo }))
        : problem(500, { code: 'INTERNAL_ERROR', status: 500 }),
    ),
  );
}

// The summary only reads the season the page has loaded: seed that cache.
function renderSummary(
  turn: ReturnType<typeof turnResultFixture>,
  currentTurnNo: number,
  totalReturn = '0.03330000',
) {
  const result = renderWithProviders(<TurnSummary turn={turn} />);
  result.queryClient.setQueryData(
    seasonKey(7),
    seasonFixture({ currentTurnNo, totalReturn }),
  );
  return result;
}

describe('TurnSummary', () => {
  it('names its region after the turn and shows value, returns, baseline and meter', async () => {
    serveContribution(5);
    const { container } = renderSummary(turnResultFixture(), 5);
    const region = screen.getByRole('region', { name: '5턴 요약' });
    expect(within(region).getByText('104,820,000원')).toBeInTheDocument();
    expect(within(region).getByText('+1.47%')).toBeInTheDocument();
    expect(await within(region).findByText('+3.33%')).toBeInTheDocument();
    expect(
      await within(region).findByText('시장 기준선보다'),
    ).toBeInTheDocument();
    expect(
      within(region).getByText('기준선 102,800,000원 (+2.80%)'),
    ).toBeInTheDocument();
    expect(within(region).getByText('+2.02%')).toBeInTheDocument();
    expect(
      within(region).getByText('-4.12% / 위험 선언 -20.00%'),
    ).toBeInTheDocument();
    const meter = within(region).getByRole('meter', {
      name: '위험 선언 대비 누적 낙폭',
    });
    expect(meter).toHaveAttribute('aria-valuenow', '20');
    expect(meter.firstElementChild).toHaveStyle({ width: '20.60%' });
    expect(
      within(region).getByText('선언 안 — 여유 15.88%p'),
    ).toBeInTheDocument();
    expectNoDatePattern(container);
  });

  it('keeps both returns while the baseline is loading', async () => {
    server.use(
      http.get('*/api/v1/seasons/7/turns/:turnNo/contribution', () => {
        return new Promise<Response>(() => {});
      }),
    );
    renderSummary(turnResultFixture(), 5);
    const region = screen.getByRole('region', { name: '5턴 요약' });
    expect(within(region).getByText('104,820,000원')).toBeInTheDocument();
    expect(within(region).getByText('+1.47%')).toBeInTheDocument();
    expect(await within(region).findByText('+3.33%')).toBeInTheDocument();
    expect(
      within(region).getByText('시장 기준선을 불러오는 중입니다…'),
    ).toBeInTheDocument();
    expect(within(region).getByRole('meter')).toBeInTheDocument();
  });

  it('keeps both returns on a baseline failure and retries on request', async () => {
    let fail = true;
    server.use(
      http.get('*/api/v1/seasons/7/turns/:turnNo/contribution', () =>
        fail
          ? problem(500, { code: 'INTERNAL_ERROR', status: 500 })
          : HttpResponse.json(contributionFixture()),
      ),
    );
    renderSummary(turnResultFixture(), 5);
    const region = screen.getByRole('region', { name: '5턴 요약' });
    const retry = await within(region).findByRole('button', {
      name: '다시 불러오기',
    });
    expect(within(region).getByText('104,820,000원')).toBeInTheDocument();
    expect(within(region).getByText('+1.47%')).toBeInTheDocument();
    expect(within(region).getByText('+3.33%')).toBeInTheDocument();
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
    fail = false;
    await userEvent.setup().click(retry);
    expect(await within(region).findByText('+2.02%')).toBeInTheDocument();
  });

  it('shows an exceeded limit with a full red meter', () => {
    serveContribution(6);
    renderSummary(turnBreached, 6);
    const region = screen.getByRole('region', { name: '6턴 요약' });
    expect(region).toHaveAttribute('data-breached', 'true');
    expect(
      within(region).getByText('초과 — 2.00%p 더 내려갔습니다'),
    ).toBeInTheDocument();
    expect(within(region).getByRole('meter')).toHaveAttribute(
      'aria-valuenow',
      '100',
    );
  });

  it('leaves the season return out on a past turn instead of using the latest', async () => {
    serveContribution(5);
    renderSummary(turnResultFixture(), 9);
    const region = screen.getByRole('region', { name: '5턴 요약' });
    expect(await within(region).findByText('+2.02%')).toBeInTheDocument();
    expect(within(region).getByText('+1.47%')).toBeInTheDocument();
    expect(within(region).queryByText(/시즌/)).toBeNull();
    expect(within(region).queryByText('+3.33%')).toBeNull();
  });

  it('asks for its own turn only and keeps nothing of the previous turn', async () => {
    serveContribution(6);
    function Harness() {
      const [later, setLater] = useState(false);
      return (
        <>
          <button
            type="button"
            onClick={() => {
              setLater(true);
            }}
          >
            다음
          </button>
          <TurnSummary turn={later ? turnBreached : turnResultFixture()} />
        </>
      );
    }
    const { queryClient } = renderWithProviders(<Harness />);
    queryClient.setQueryData(
      seasonKey(7),
      seasonFixture({ currentTurnNo: 6, totalReturn: '0.05550000' }),
    );
    expect(
      await screen.findByRole('region', { name: '5턴 요약' }),
    ).toBeInTheDocument();
    expect(
      await screen.findByText(/시장 기준선을 불러오지 못했습니다/),
    ).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: '다음' }));
    const region = screen.getByRole('region', { name: '6턴 요약' });
    expect(await within(region).findByText('+5.55%')).toBeInTheDocument();
    expect(await within(region).findByText('+2.02%')).toBeInTheDocument();
    expect(within(region).queryByText('+3.33%')).toBeNull();
  });
});

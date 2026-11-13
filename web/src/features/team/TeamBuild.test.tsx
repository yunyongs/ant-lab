import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { holdingsFixture, heldDefender } from '../../test/fixtures/holdings.ts';
import { expectNoDatePattern } from '../../test/expectNoDatePattern.ts';
import { renderRoute } from '../../test/renderWithProviders.tsx';
import { serveSeason } from '../../test/serve.ts';
import { server } from '../../test/server.ts';
import { turnResultFixture } from '../../test/fixtures/turns.ts';
import { gate } from '../../test/serveTurns.ts';

describe('TeamBuild', () => {
  it('shows the team build only at turn 0 of an open season', async () => {
    serveSeason({});
    const first = renderRoute('/seasons/7');
    expect(
      await screen.findByRole('heading', { name: '0턴 팀 구성' }),
    ).toBeInTheDocument();
    first.unmount();

    serveSeason({ currentTurnNo: 1 });
    const second = renderRoute('/seasons/7');
    expect(
      await screen.findByRole('heading', { name: '1턴 결과' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '0턴 팀 구성' })).toBeNull();
    second.unmount();

    serveSeason({ status: 'FINISHED', currentTurnNo: 12 });
    renderRoute('/seasons/7');
    expect(await screen.findByText('시즌이 끝났습니다')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '0턴 팀 구성' })).toBeNull();
  });

  it('shows the server strings for cash, weights and targets', async () => {
    serveSeason({});
    renderRoute('/seasons/7');
    const summary = await screen.findByRole('region', { name: '팀 요약' });
    expect(within(summary).getAllByText('100,000,000원')).toHaveLength(2);
    expect(within(summary).getByText('100% / 목표 40%')).toBeInTheDocument();
    expect(within(summary).getByText('0% / 목표 30%')).toBeInTheDocument();
    expect(within(summary).getByText('0% / 목표 20%')).toBeInTheDocument();
    expect(within(summary).getByText('0% / 목표 10%')).toBeInTheDocument();
    expect(
      within(summary).getByText(/골키퍼에는 현금이 포함됩니다/),
    ).toBeInTheDocument();
    const fill = within(summary).getByTestId('fill-goalkeeper');
    expect(parseCssPercent(fill.style.width)).toBe('100');
  });

  it('shows weights exactly as sent, even when they do not add to 100%', async () => {
    serveSeason({});
    server.use(
      http.get('*/api/v1/seasons/7/holdings', () =>
        HttpResponse.json(
          holdingsFixture({
            goalkeeperWeight: '0.40000000',
            defenderWeight: '0.30000000',
            midfielderWeight: '0.20000000',
            forwardWeight: '0.09999999',
          }),
        ),
      ),
    );
    renderRoute('/seasons/7');
    const summary = await screen.findByRole('region', { name: '팀 요약' });
    expect(within(summary).getByText('40% / 목표 40%')).toBeInTheDocument();
    expect(
      within(summary).getByText('9.999999% / 목표 10%'),
    ).toBeInTheDocument();
    // jsdom rounds long CSS values; the exact string is covered in ratio.test.
    expect(within(summary).getByTestId('fill-goalkeeper').style.width).toBe(
      '40%',
    );
  });

  it('explains the next step for no holdings and for one or more', async () => {
    serveSeason({});
    const first = renderRoute('/seasons/7');
    const card = await screen.findByRole('region', { name: '다음 단계' });
    expect(card).toHaveTextContent(
      '아직 산 종목이 없습니다. 이대로 넘기면 전부 현금(골키퍼)으로 시작합니다.',
    );
    expect(
      within(card).getByRole('button', { name: '다음 턴 (1턴으로)' }),
    ).toBeInTheDocument();
    first.unmount();

    server.use(
      http.get('*/api/v1/seasons/7/holdings', () =>
        HttpResponse.json(holdingsFixture({ holdings: [heldDefender] })),
      ),
    );
    renderRoute('/seasons/7');
    const second = await screen.findByRole('region', { name: '다음 단계' });
    await within(second).findByText(/팀 구성을 마쳤으면/);
    expect(second).toHaveTextContent(
      '넘긴 뒤에는 턴 사이에만 매매할 수 있습니다.',
    );
    expect(
      within(second).getByRole('button', { name: '다음 턴 (1턴으로)' }),
    ).toBeInTheDocument();
  });

  it('disables every pool selection while a turn advance is pending', async () => {
    serveSeason({});
    const hold = gate();
    server.use(
      http.post('*/api/v1/seasons/7/turns', async () => {
        await hold.promise;
        return HttpResponse.json(turnResultFixture({ turnNo: 1 }), {
          status: 201,
        });
      }),
    );
    const user = userEvent.setup();
    renderRoute('/seasons/7');
    const card = await screen.findByRole('region', { name: '다음 단계' });
    await screen.findByRole('button', { name: '종목 가 선택' });
    await user.click(
      within(card).getByRole('button', { name: '다음 턴 (1턴으로)' }),
    );
    await waitFor(() => {
      for (const button of screen.getAllByRole('button', { name: /선택$/ })) {
        expect(button).toBeDisabled();
      }
    });
    hold.release();
  });

  it('shows no real date anywhere, even with the order sheet open', async () => {
    serveSeason({});
    const user = userEvent.setup();
    const { container } = renderRoute('/seasons/7');
    await user.click(
      await screen.findByRole('button', { name: '종목 가 선택' }),
    );
    await screen.findByRole('dialog', { name: '종목 가' });
    expectNoDatePattern(container);
    expectNoDatePattern(document.body);
  });
});

// jsdom may normalise "62.000000%" to "62%": compare the number only.
function parseCssPercent(width: string): string {
  return width.replace('%', '').replace(/\.0+$/, '');
}

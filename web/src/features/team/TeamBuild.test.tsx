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

  it('shows cash, the target and current weights and the buy hint', async () => {
    serveSeason({});
    renderRoute('/seasons/7');
    const summary = await screen.findByRole('region', { name: '팀 요약' });
    expect(within(summary).getByText('남은 현금')).toBeInTheDocument();
    expect(within(summary).getByText('100,000,000원')).toBeInTheDocument();
    expect(within(summary).getByText('목표 포메이션')).toBeInTheDocument();
    expect(within(summary).getByText('골키퍼 100.00% → 40%')).toBeVisible();
    expect(within(summary).getByText('수비수 0.00% → 30%')).toBeVisible();
    expect(within(summary).getByText('미드필더 0.00% → 20%')).toBeVisible();
    expect(within(summary).getByText('공격수 0.00% → 10%')).toBeVisible();
    expect(
      within(summary).getByText(/골키퍼에는 현금이 포함됩니다/),
    ).toHaveTextContent(
      '수비수 약 3,000만 원, 미드필더 약 2,000만 원, 공격수 약 1,000만 원을 사면 목표와 맞습니다.',
    );
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
    expect(within(summary).getByText('골키퍼 40.00% → 40%')).toBeVisible();
    expect(within(summary).getByText('공격수 10.00% → 10%')).toBeVisible();
    // jsdom rounds long CSS values; the exact string is covered in ratio.test.
    expect(within(summary).getByTestId('fill-goalkeeper').style.width).toBe(
      '40%',
    );
  });

  it('labels the start bar by holdings and sends one advance request', async () => {
    serveSeason({});
    const calls = { advance: 0 };
    server.use(
      http.post('*/api/v1/seasons/7/turns', () => {
        calls.advance += 1;
        return HttpResponse.json(turnResultFixture({ turnNo: 1 }), {
          status: 201,
        });
      }),
    );
    const user = userEvent.setup();
    const first = renderRoute('/seasons/7');
    const card = await screen.findByRole('region', { name: '다음 단계' });
    expect(card).toHaveTextContent(
      '아직 산 종목이 없습니다. 이대로 넘기면 전부 현금(골키퍼)으로 시작합니다.',
    );
    const start = within(card).getByRole('button', {
      name: '구성 마치고 1턴 시작',
    });
    await user.dblClick(start);
    await waitFor(() => {
      expect(calls.advance).toBe(1);
    });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(calls.advance).toBe(1);
    first.unmount();

    server.use(
      http.get('*/api/v1/seasons/7/holdings', () =>
        HttpResponse.json(holdingsFixture({ holdings: [heldDefender] })),
      ),
    );
    renderRoute('/seasons/7');
    const second = await screen.findByRole('region', { name: '다음 단계' });
    await within(second).findByText('남은 현금은 골키퍼로 1턴을 맞습니다');
    expect(
      within(second).getByRole('button', { name: '구성 마치고 1턴 시작' }),
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
    await screen.findByRole('button', { name: '종목 가 사기' });
    await user.click(
      within(card).getByRole('button', { name: '구성 마치고 1턴 시작' }),
    );
    await waitFor(() => {
      for (const button of screen.getAllByRole('button', { name: /사기$/ })) {
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
      await screen.findByRole('button', { name: '종목 가 사기' }),
    );
    await screen.findByRole('dialog', { name: '종목 가' });
    expectNoDatePattern(container);
    expectNoDatePattern(document.body);
  });
});

// jsdom may normalise "62.00%" to "62.00%": compare the number only.
function parseCssPercent(width: string): string {
  return width.replace('%', '').replace(/\.0+$/, '');
}

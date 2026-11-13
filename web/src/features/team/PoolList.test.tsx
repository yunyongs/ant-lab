import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../../test/authHandlers.ts';
import { poolAssetFixture } from '../../test/fixtures/pool.ts';
import { heldDefender, holdingsFixture } from '../../test/fixtures/holdings.ts';
import { renderRoute } from '../../test/renderWithProviders.tsx';
import { serveSeason } from '../../test/serve.ts';
import { server } from '../../test/server.ts';

describe('PoolList', () => {
  it('shows each asset with its position label and basis strings verbatim', async () => {
    serveSeason({});
    const { container } = renderRoute('/seasons/7');
    const list = await screen.findByRole('region', { name: '종목 풀' });
    const cards = await within(list).findAllByRole('listitem');
    expect(cards).toHaveLength(3);
    const [first, second, third] = cards;
    expect(first).toHaveTextContent('종목 가');
    expect(first).toHaveTextContent('KR');
    expect(first).toHaveTextContent('미드필더');
    expect(first).toHaveTextContent('0.91000000');
    expect(first).toHaveTextContent('-0.31000000');
    expect(second).toHaveTextContent('US');
    expect(second).toHaveTextContent('공격수·돌파형');
    expect(third).toHaveTextContent('채권 ETF 다');
    expect(third).toHaveTextContent('수비수');
    for (const label of ['상승 참여', '하락 참여', '변동성', '최대 낙폭']) {
      expect(within(first as HTMLElement).getByText(label)).toBeInTheDocument();
    }
    expect(container.textContent).not.toContain('FIX-AAA');
    expect(container.textContent).not.toContain('시험 추종 지수');
    expect(container.textContent).not.toContain('시험 업종');
  });

  it('shows a held badge from the holdings', async () => {
    serveSeason({});
    server.use(
      http.get('*/api/v1/seasons/7/holdings', () =>
        HttpResponse.json(holdingsFixture({ holdings: [heldDefender] })),
      ),
    );
    renderRoute('/seasons/7');
    expect(
      await screen.findByText('보유 30주 · 수비수 선언'),
    ).toBeInTheDocument();
    expect(screen.getAllByText(/보유 \d+주/)).toHaveLength(1);
  });

  it.each([
    [
      'POOL_NOT_LOADED',
      () => problem(409, { code: 'POOL_NOT_LOADED', status: 409 }),
      '종목 풀이 아직 준비되지 않았습니다.',
    ],
    [
      'POOL_NOT_JUDGED',
      () => problem(409, { code: 'POOL_NOT_JUDGED', status: 409 }),
      '종목 풀의 포지션 판정이 끝나지 않았습니다.',
    ],
    [
      'an empty pool',
      () => HttpResponse.json({ assets: [] }),
      '종목 풀이 비어 있습니다.',
    ],
    [
      'an unreachable API',
      () => HttpResponse.error(),
      'API에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요.',
    ],
  ])(
    'shows the empty state for %s and retries',
    async (_name, answer, line) => {
      serveSeason({});
      let failing = true;
      server.use(
        http.get('*/api/v1/pool', () =>
          failing
            ? answer()
            : HttpResponse.json({ assets: [poolAssetFixture()] }),
        ),
      );
      const user = userEvent.setup();
      renderRoute('/seasons/7');
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('종목 풀을 불러오지 못했습니다');
      expect(alert).toHaveTextContent(line);
      expect(screen.queryByRole('button', { name: /선택$/ })).toBeNull();
      failing = false;
      await user.click(
        within(alert).getByRole('button', { name: '다시 불러오기' }),
      );
      expect(
        await screen.findByRole('button', { name: '종목 가 선택' }),
      ).toBeInTheDocument();
      expect(screen.queryByText('종목 풀을 불러오지 못했습니다')).toBeNull();
    },
  );
});

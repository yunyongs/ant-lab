import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { parseWholeNumber } from '../features/format/integer.ts';
import { problem } from '../test/authHandlers.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import {
  contributionFixture,
  holdingContributionFixture,
} from '../test/fixtures/contribution.ts';
import { poolAssetFixture } from '../test/fixtures/pool.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveTurnWorld } from '../test/serveTurns.ts';
import { server } from '../test/server.ts';
import { settle } from '../test/settle.ts';

const TITLE = '이번 턴 기여';

// Eight holdings in the server's order (largest contribution first): four up,
// one zero, three down. Names come from the pool by assetId.
const CONTRIBUTIONS = [
  [901, '5000000'],
  [902, '3000000'],
  [903, '2000000'],
  [904, '1000000'],
  [905, '0'],
  [906, '-400000'],
  [907, '-600000'],
  [908, '-900000'],
] as const;

function serveEight() {
  const assets = CONTRIBUTIONS.map(([id], i) =>
    poolAssetFixture({ assetId: id, name: `시험 ${String(i + 1)}` }),
  );
  const holdings = CONTRIBUTIONS.map(([id, contribution]) =>
    holdingContributionFixture({
      assetId: id,
      contribution,
    }),
  );
  server.use(
    http.get('*/api/v1/pool', () => HttpResponse.json({ assets })),
    http.get('*/api/v1/seasons/7/turns/:turnNo/contribution', () =>
      HttpResponse.json(contributionFixture({ turnNo: 4, holdings })),
    ),
  );
}

function countContributionGets(): number[] {
  const gets: number[] = [];
  server.use(
    http.get('*/api/v1/seasons/7/turns/:turnNo/contribution', ({ params }) => {
      const turnNo = parseWholeNumber(String(params.turnNo), 1000) ?? 0;
      gets.push(turnNo);
      return HttpResponse.json(contributionFixture({ turnNo }));
    }),
  );
  return gets;
}

async function openSection() {
  const region = await screen.findByRole('region', { name: TITLE });
  await within(region).findByRole('heading', { name: '기준 대비' });
  return region;
}

function names(list: HTMLElement): string[] {
  return within(list)
    .getAllByRole('listitem')
    .map((item) => item.firstElementChild?.textContent ?? '');
}

function listUnder(region: HTMLElement, heading: string): HTMLElement {
  const title = within(region).getByRole('heading', { name: heading });
  const list = title.parentElement?.querySelector('ul');
  if (!(list instanceof HTMLElement)) throw new Error(`no list: ${heading}`);
  return list;
}

describe('turn contribution on the turn screen', () => {
  it('shows the top three up and down from the server order, the rest behind 전체 보기', async () => {
    serveTurnWorld({ current: 4 });
    serveEight();
    renderRoute('/seasons/7');
    const region = await openSection();
    expect(names(listUnder(region, '많이 더한 종목'))).toEqual([
      '시험 1',
      '시험 2',
      '시험 3',
    ]);
    // Largest loss first.
    expect(names(listUnder(region, '많이 뺀 종목'))).toEqual([
      '시험 8',
      '시험 7',
      '시험 6',
    ]);
    expect(within(region).queryByText('시험 4')).toBeNull();
    expect(within(region).queryByText('시험 5')).toBeNull();

    const user = userEvent.setup();
    const more = within(region).getByRole('button', {
      name: '전체 보기 (8종목)',
    });
    expect(more).toHaveAttribute('aria-expanded', 'false');
    await user.click(more);
    expect(more).toHaveAttribute('aria-expanded', 'true');
    expect(more).toHaveTextContent('전체 접기');
    const all = listUnder(region, '전체 종목 기여');
    expect(names(all)).toEqual(
      CONTRIBUTIONS.map((_, i) => `시험 ${String(i + 1)}`),
    );
    const top = within(all).getAllByRole('listitem')[0];
    expect(top).toBeDefined();
    expect(
      within(top as HTMLElement).getByText('+5,000,000원'),
    ).toHaveAttribute('data-direction', 'up');
    const zero = within(all).getAllByRole('listitem')[4] as HTMLElement;
    expect(within(zero).getByText('0원')).toHaveAttribute(
      'data-direction',
      'flat',
    );
    await user.click(more);
    expect(
      within(region).queryByRole('heading', { name: '전체 종목 기여' }),
    ).toBeNull();
  });

  it('shows the sums, the benchmark row and the positions as whole won and 2-decimal percent', async () => {
    serveTurnWorld({ current: 4 });
    const { container } = renderRoute('/seasons/7');
    const region = await openSection();
    expect(within(region).getByText('+1,537,440원')).toHaveAttribute(
      'data-direction',
      'up',
    );
    expect(within(region).getByText('17,440원')).toBeInTheDocument();
    expect(within(region).getByText('+4.82%')).toBeInTheDocument();
    expect(within(region).getByText('102,800,000원')).toBeInTheDocument();
    expect(within(region).getByText('+2.80%')).toBeInTheDocument();
    expect(within(region).getByText('+2.02%')).toHaveAttribute(
      'data-direction',
      'up',
    );
    const positions = listUnder(region, '포지션별 기여');
    expect(names(positions)).toEqual([
      '골키퍼 (현금 포함)',
      '수비수',
      '미드필더',
      '공격수',
    ]);
    const forward = within(positions).getAllByRole(
      'listitem',
    )[3] as HTMLElement;
    expect(within(forward).getByText('-230,000원')).toHaveAttribute(
      'data-direction',
      'down',
    );
    expect(within(forward).getByText('-0.22%')).toBeInTheDocument();
    // Three holdings fit in the top lists with the zero one left over.
    expect(
      within(region).getByRole('button', { name: '전체 보기 (3종목)' }),
    ).toBeInTheDocument();
    expect(region.textContent).not.toMatch(/\d\.\d{3,}/);
    expectNoDatePattern(container);
  });

  it('says so when every contribution is zero or nothing was held', async () => {
    serveTurnWorld({ current: 4 });
    server.use(
      http.get('*/api/v1/seasons/7/turns/:turnNo/contribution', () =>
        HttpResponse.json(
          contributionFixture({
            turnNo: 4,
            holdingsContribution: '0',
            holdings: [holdingContributionFixture({ contribution: '0' })],
          }),
        ),
      ),
    );
    renderRoute('/seasons/7');
    const region = await openSection();
    expect(
      within(region).getByText('값을 더한 종목이 없습니다.'),
    ).toBeInTheDocument();
    expect(
      within(region).getByText('값을 뺀 종목이 없습니다.'),
    ).toBeInTheDocument();
    expect(
      within(region).getByRole('button', { name: '전체 보기 (1종목)' }),
    ).toBeInTheDocument();
  });

  it('says so when nothing was held through the move', async () => {
    serveTurnWorld({ current: 4 });
    server.use(
      http.get('*/api/v1/seasons/7/turns/:turnNo/contribution', () =>
        HttpResponse.json(contributionFixture({ turnNo: 4, holdings: [] })),
      ),
    );
    renderRoute('/seasons/7');
    const region = await openSection();
    expect(
      within(region).getByText(
        '이 턴으로 넘어오는 동안 보유한 종목이 없습니다.',
      ),
    ).toBeInTheDocument();
    expect(
      within(region).queryByRole('button', { name: /전체 보기/ }),
    ).toBeNull();
  });

  it('keeps a failure inside the section and retries from there', async () => {
    serveTurnWorld({ current: 4 });
    let fail = true;
    server.use(
      http.get('*/api/v1/seasons/7/turns/:turnNo/contribution', () =>
        fail
          ? problem(500, { code: 'INTERNAL_ERROR', status: 500 })
          : HttpResponse.json(contributionFixture({ turnNo: 4 })),
      ),
    );
    renderRoute('/seasons/7');
    const region = await screen.findByRole('region', { name: TITLE });
    const alert = await within(region).findByRole('alert');
    expect(alert).toHaveTextContent('서버에 문제가 생겼습니다');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    // The rest of the turn screen is still there.
    expect(screen.getByRole('region', { name: '턴 결과' })).toBeInTheDocument();
    expect(
      screen.getByRole('region', { name: '4턴 요약' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('group', { name: '턴 행동' })).toBeInTheDocument();
    fail = false;
    const user = userEvent.setup();
    await user.click(
      within(alert).getByRole('button', { name: '다시 불러오기' }),
    );
    await within(region).findByRole('heading', { name: '기준 대비' });
    expect(within(region).queryByRole('alert')).toBeNull();
  });

  it('is a collapsible section that is open by default and stops asking when closed', async () => {
    serveTurnWorld({ current: 4 });
    const gets = countContributionGets();
    renderRoute('/seasons/7');
    const toggle = await screen.findByRole('button', { name: /^이번 턴 기여/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const region = await openSection();
    expect(gets).toEqual([4]);
    // Heading levels: turn heading (2), section (3), sub-groups (4).
    expect(
      screen.getByRole('heading', { level: 2, name: '4턴 결과' }),
    ).toBeInTheDocument();
    expect(
      within(region).getByRole('heading', { level: 3, name: /이번 턴 기여/ }),
    ).toBeInTheDocument();
    for (const name of [
      '기준 대비',
      '많이 더한 종목',
      '많이 뺀 종목',
      '포지션별 기여',
    ]) {
      expect(
        within(region).getByRole('heading', { level: 4, name }),
      ).toBeInTheDocument();
    }
    expect(within(region).getAllByRole('list').length).toBeGreaterThanOrEqual(
      3,
    );
    const user = userEvent.setup();
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(
      within(region).queryByRole('heading', { name: '기준 대비' }),
    ).toBeNull();
  });

  it('shows the past turn it belongs to and never asks for a later turn', async () => {
    serveTurnWorld({ current: 5 });
    const gets = countContributionGets();
    renderRoute('/seasons/7/turns/3');
    const region = await screen.findByRole('region', { name: '이 턴의 기여' });
    await within(region).findByRole('heading', { name: '기준 대비' });
    expect(gets).toEqual([3]);
  });

  it('has no section and sends no request for an out-of-range turn', async () => {
    serveTurnWorld({ current: 5 });
    const gets = countContributionGets();
    renderRoute('/seasons/7/turns/6');
    await screen.findByText('아직 진행하지 않은 턴입니다.');
    expect(screen.queryByRole('region', { name: '이 턴의 기여' })).toBeNull();
    expect(gets).toEqual([]);
  });

  it('has no section at turn 0', async () => {
    serveTurnWorld({ current: 0 });
    const gets = countContributionGets();
    const { queryClient } = renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '시즌 7' });
    await settle(queryClient);
    expect(screen.queryByRole('region', { name: TITLE })).toBeNull();
    expect(screen.queryByText(/턴 기여/)).toBeNull();
    expect(gets).toEqual([]);
  });
});

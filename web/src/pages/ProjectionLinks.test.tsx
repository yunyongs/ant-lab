import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveProjection } from '../test/serveProjection.ts';

const LINK = { name: '예상 분포 보기' } as const;

async function settle() {
  await new Promise((resolve) => setTimeout(resolve, 60));
}

// The projection is one slow engine call: rendering an entry point must never
// ask for it, only following the link does.
describe('projection entry links', () => {
  it('P10: the turn screen links to the page and requests nothing', async () => {
    const world = serveProjection();
    renderRoute('/seasons/7');
    const link = await screen.findByRole('link', LINK);
    expect(link).toHaveAttribute('href', '/seasons/7/projection');
    await settle();
    expect(world.phases).toEqual([]);
  });

  it('P10: the trade screen links to the page and requests nothing', async () => {
    const world = serveProjection({ season: { currentTurnNo: 0 } });
    renderRoute('/seasons/7/trade');
    const link = await screen.findByRole('link', LINK);
    expect(link).toHaveAttribute('href', '/seasons/7/projection');
    await settle();
    expect(world.phases).toEqual([]);
  });

  it('P10: the finished screen links to the page and requests nothing', async () => {
    const world = serveProjection({
      season: { status: 'FINISHED', currentTurnNo: 12 },
    });
    renderRoute('/seasons/7');
    const link = await screen.findByRole('link', LINK);
    expect(link).toHaveAttribute('href', '/seasons/7/projection');
    await settle();
    expect(world.phases).toEqual([]);
  });

  it('P10: following the link requests the current team once', async () => {
    const world = serveProjection();
    renderRoute('/seasons/7');
    await userEvent.click(await screen.findByRole('link', LINK));
    await screen.findByRole('img', { name: /예상 분포 띠 그림/ });
    await waitFor(() => {
      expect(world.phases).toEqual(['CURRENT']);
    });
  });
});

import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { APP_NAME } from './appName.ts';

describe('AppShell', () => {
  it('renders header, main and the labelled bottom navigation', async () => {
    renderRoute('/');
    expect(
      within(screen.getByRole('banner')).getByText(APP_NAME),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('main')).toHaveLength(1);
    const nav = screen.getByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '홈' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    await screen.findByText(/API 연결됨/);
  });
});

import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// The ranking section of the now page starts collapsed.
export async function expandRanking(): Promise<HTMLElement> {
  const region = await screen.findByRole('region', { name: '순위' });
  const toggle = await screen.findByRole('button', { name: /^순위/ });
  if (toggle.getAttribute('aria-expanded') === 'false') {
    await userEvent.setup().click(toggle);
  }
  return region;
}

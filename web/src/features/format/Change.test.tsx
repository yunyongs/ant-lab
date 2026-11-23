import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Change } from './Change.tsx';

describe('Change', () => {
  it.each([
    ['0.0147', '+1.47%', 'up'],
    ['-0.31', '-31.00%', 'down'],
    ['-0.00000001', '0.00%', 'flat'],
  ] as const)('%s shows %s as %s', (value, text, direction) => {
    render(<Change value={value} kind="percent" />);
    expect(screen.getByText(text)).toHaveAttribute('data-direction', direction);
  });
});

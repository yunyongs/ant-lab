import { expect } from 'vitest';

// Before a season ends no real date may reach the screen (PLAN §2).
const DATE_PATTERN = /\b(19|20)\d{2}[-./년]\s?\d{1,2}/;

export function expectNoDatePattern(container: HTMLElement): void {
  expect(container.textContent).not.toMatch(DATE_PATTERN);
  expect(container.innerHTML).not.toMatch(DATE_PATTERN);
}

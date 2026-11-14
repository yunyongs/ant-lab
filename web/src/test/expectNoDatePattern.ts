import { expect } from 'vitest';
import { DATE_PATTERN } from './datePattern.ts';

export { DATE_PATTERN };

export function expectNoDatePattern(container: HTMLElement): void {
  expect(container.textContent).not.toMatch(DATE_PATTERN);
  expect(container.innerHTML).not.toMatch(DATE_PATTERN);
}

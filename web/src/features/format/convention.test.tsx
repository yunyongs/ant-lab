import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConventionToggle } from './ConventionToggle.tsx';
import {
  getConvention,
  initConvention,
  resetConventionForTests,
} from './convention.ts';

// Storage is read through the global in the module under test; this file
// stubs it to check the stored value and the failure fallback.
function stubStorage(store: Record<string, string>) {
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => {
      store[key] = value;
    },
  });
}

describe('up/down convention', () => {
  beforeEach(() => {
    resetConventionForTests();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    resetConventionForTests();
  });

  it('defaults to the Korean convention and marks the document', () => {
    stubStorage({});
    initConvention();
    expect(getConvention()).toBe('korean');
    expect(document.documentElement.dataset.updown).toBe('korean');
  });

  it('switches, remembers the choice and restores it', async () => {
    const store: Record<string, string> = {};
    stubStorage(store);
    render(<ConventionToggle />);
    const button = screen.getByRole('button', {
      name: '등락 색: 상승 빨강 · 하락 파랑. 누르면 상승 초록 · 하락 빨강(으)로 바꿉니다',
    });
    expect(button).not.toHaveAttribute('aria-pressed');
    await userEvent.setup().click(button);
    expect(
      screen.getByRole('button', {
        name: '등락 색: 상승 초록 · 하락 빨강. 누르면 상승 빨강 · 하락 파랑(으)로 바꿉니다',
      }),
    ).toBeInTheDocument();
    expect(document.documentElement.dataset.updown).toBe('western');
    expect(store['antlab.updown']).toBe('western');

    resetConventionForTests();
    expect(getConvention()).toBe('western');
  });

  it('works when storage throws', async () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    });
    expect(getConvention()).toBe('korean');
    render(<ConventionToggle />);
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: /^등락 색: 상승 빨강/ }));
    expect(getConvention()).toBe('western');
  });
});

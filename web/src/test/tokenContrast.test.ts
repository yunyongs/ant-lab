// @vitest-environment node
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const css = readFileSync(
  fileURLToPath(new URL('../styles/tokens.css', import.meta.url)),
  'utf8',
);

function token(name: string): string {
  const match = new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})`).exec(css);
  if (match?.[1] === undefined) throw new Error(`missing token ${name}`);
  return match[1];
}

function channel(hex: string, at: number): number {
  const value = parseInt(hex.slice(at, at + 2), 16) / 255;
  return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
}

function luminance(hex: string): number {
  return (
    0.2126 * channel(hex, 1) +
    0.7152 * channel(hex, 3) +
    0.0722 * channel(hex, 5)
  );
}

function contrast(a: string, b: string): number {
  const [hi = 0, lo = 0] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe('up/down colour contrast', () => {
  const surfaces = ['--color-surface', '--color-bg'];
  it.each([
    '--color-up-korean',
    '--color-down-korean',
    '--color-up-western',
    '--color-down-western',
  ])('%s is at least 4.5:1 on the surfaces', (name) => {
    for (const surface of surfaces) {
      expect(contrast(token(name), token(surface))).toBeGreaterThanOrEqual(4.5);
    }
  });
});

describe('up/down token wiring', () => {
  it('maps the aliases to the Korean tokens and western to the western ones', () => {
    const root = /:root\s*\{[^}]*\}/.exec(css)?.[0] ?? '';
    expect(root).toContain('--color-up: var(--color-up-korean)');
    expect(root).toContain('--color-down: var(--color-down-korean)');
    const western = /:root\[data-updown='western'\]\s*\{([^}]*)\}/.exec(css);
    expect(western?.[1]).toContain('--color-up: var(--color-up-western)');
    expect(western?.[1]).toContain('--color-down: var(--color-down-western)');
  });
});

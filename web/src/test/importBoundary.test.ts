import { describe, expect, it } from 'vitest';

// Review-only values (feed identities, hindsight) live behind the review and
// analysis features; screens of a running season must not import them.
const sources = import.meta.glob<string>(
  [
    '../features/{feed,turn,ranking,team,trade,projection,abandon,valueline,chart}/**/*.{ts,tsx}',
    '../pages/{Season,Turn,Trade,FormationChange,Projection,NewSeason}Page.tsx',
  ],
  { query: '?raw', import: 'default', eager: true },
);
// Static `from '…'`, bare `import '…'` and dynamic `import('…')`.
const FORBIDDEN =
  /(?:from|import)\s*\(?\s*['"][^'"]*\/(review|analysis)(\/[^'"]*)?['"]/;

describe('import boundary', () => {
  it('R11: running-season code imports nothing from review or analysis', () => {
    const files = Object.entries(sources);
    expect(files.length).toBeGreaterThan(10);
    const names = files.map(([file]) => file);
    expect(names.some((n) => n.includes('/projection/'))).toBe(true);
    expect(names.some((n) => n.includes('/abandon/'))).toBe(true);
    expect(names.some((n) => n.endsWith('/SeasonPage.tsx'))).toBe(true);
    for (const [file, text] of files) {
      expect(text, file).not.toMatch(FORBIDDEN);
    }
  });

  it('R11: the pattern catches static, bare, relative and dynamic forms', () => {
    expect(`import { x } from '../review/labels.ts';`).toMatch(FORBIDDEN);
    expect(`import { x } from '../../features/analysis';`).toMatch(FORBIDDEN);
    expect(`const m = await import('../review/labels.ts');`).toMatch(FORBIDDEN);
    expect(`import '../analysis/side.ts';`).toMatch(FORBIDDEN);
    expect(`import { x } from '../format/money.ts';`).not.toMatch(FORBIDDEN);
  });
});

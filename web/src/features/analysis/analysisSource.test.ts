import { describe, expect, it } from 'vitest';

// Raw source text of every non-test module under src/features.
const modules = import.meta.glob<string>(
  ['../**/*.ts', '../**/*.tsx', '!../**/*.test.ts', '!../**/*.test.tsx'],
  { query: '?raw', import: 'default', eager: true },
);

function sources(folder: string): [string, string][] {
  // Files next to this test come back as `./name`.
  const prefix = folder === 'analysis' ? './' : `../${folder}/`;
  return Object.entries(modules).filter(([path]) => path.startsWith(prefix));
}

describe('analysis source rules', () => {
  it('X1 does no arithmetic, sorting or reduction on server fields', () => {
    const files = sources('analysis');
    expect(files.length).toBeGreaterThan(5);
    for (const [name, text] of files) {
      expect(text, name).not.toMatch(/\.reduce\(|\.sort\(|\.toSorted\(/);
      expect(text, name).not.toMatch(
        /\.(fee|totalFee|teamFee|nudgedFee|\w*Notional|\w*Return|effectWon|goalWon)\s*[-+*/]\s*\S/,
      );
      expect(text, name).not.toMatch(
        /Object\.(entries|values)\(|BigInt\(|Math\./,
      );
    }
  });

  it('keeps the feed, turn, ranking, team and trade features independent of analysis', () => {
    for (const folder of ['feed', 'turn', 'ranking', 'team', 'trade']) {
      const files = sources(folder);
      expect(files.length, folder).toBeGreaterThan(0);
      for (const [name, text] of files) {
        expect(text, name).not.toMatch(/\/analysis\//);
        expect(text, name).not.toMatch(/\/review\//);
      }
    }
  });
});

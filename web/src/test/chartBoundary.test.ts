import { describe, expect, it } from 'vitest';

const sources = import.meta.glob<string>(
  ['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}'],
  {
    query: '?raw',
    import: 'default',
    eager: true,
  },
);

const LIBRARY =
  /from\s+['"]lightweight-charts['"]|import\s*\(\s*['"]lightweight-charts['"]/;
const STATIC_CHART = /from\s+['"][^'"]*\/ValueLineChart(\.tsx)?['"]/;
const NUMBER_CALL = /(?<![.\w])Number\s*\(/;

// The chart library is only reached through a dynamic import, so the build
// puts it in its own chunk outside the main bundle.
describe('chart boundary', () => {
  const files = Object.entries(sources);

  it('imports the chart library from one module only', () => {
    expect(files.length).toBeGreaterThan(50);
    const users = files
      .filter(([, text]) => LIBRARY.test(text))
      .map(([file]) => file);
    expect(users).toEqual(['../features/chart/ValueLineChart.tsx']);
  });

  it('loads that module only lazily', () => {
    for (const [file, text] of files) {
      expect(text, file).not.toMatch(STATIC_CHART);
    }
    const section = sources['../features/valueline/ValueSection.tsx'];
    expect(section).toMatch(
      /lazy\(\(\) => import\('\.\.\/chart\/ValueLineChart\.tsx'\)\)/,
    );
  });

  it('converts strings with Number only in the chart adapter', () => {
    const users = files
      .filter(([, text]) => NUMBER_CALL.test(text))
      .map(([file]) => file);
    expect(users).toEqual(['../features/chart/adapter.ts']);
  });
});

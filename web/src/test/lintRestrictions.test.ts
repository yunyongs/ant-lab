// @vitest-environment node
import { fileURLToPath } from 'node:url';
import { ESLint } from 'eslint';
import { describe, expect, it } from 'vitest';
import { restrictionRules } from '../../eslint.restrictions.js';

const eslint = new ESLint({
  overrideConfigFile: true,
  overrideConfig: [
    {
      files: ['**/*.ts'],
      languageOptions: { sourceType: 'module' },
      rules: restrictionRules,
    },
  ],
});

async function messages(code: string) {
  const [result] = await eslint.lintText(code, { filePath: 'sample.ts' });
  return result?.messages ?? [];
}

describe('lint restrictions', () => {
  it.each([
    ['parseFloat("1")', /decimal strings/],
    ['Number("1")', /decimal strings/],
    ['new Number("1")', /decimal strings/],
    ['Number.parseFloat("1")', /decimal strings/],
    ['localStorage.getItem("k")', /memory only/],
    ['sessionStorage.getItem("k")', /memory only/],
    ['window.localStorage', /memory only/],
    ['window.sessionStorage', /memory only/],
    ['globalThis.localStorage', /memory only/],
    ['document.cookie', /memory only/],
  ])('rejects %s with its reason', async (code, reason) => {
    const found = await messages(code);
    expect(found).toHaveLength(1);
    expect(found[0]?.message).toMatch(reason);
  });

  it.each(['Number.isInteger(1)', 'String(1)', 'const n = 1; `${n}`'])(
    'allows %s',
    async (code) => {
      expect(await messages(code)).toHaveLength(0);
    },
  );
});

describe('real eslint.config.js', () => {
  const real = new ESLint({
    cwd: fileURLToPath(new URL('../../', import.meta.url)),
  });

  it.each(['ts', 'tsx', 'mts', 'js', 'jsx', 'mjs', 'cjs'])(
    'applies the restrictions to src/probe.%s',
    async (ext) => {
      const config = (await real.calculateConfigForFile(
        `src/probe.${ext}`,
      )) as { rules?: Record<string, unknown[] | number | string> };
      for (const rule of [
        'no-restricted-globals',
        'no-restricted-properties',
        'no-restricted-syntax',
      ]) {
        const setting = config.rules?.[rule];
        const severity = Array.isArray(setting) ? setting[0] : setting;
        expect([2, 'error'], `${rule} for .${ext}`).toContain(severity);
      }
    },
  );

  it('rejects Number() in a plain .js file under src', async () => {
    const [result] = await real.lintText("export const x = Number('1');\n", {
      filePath: 'src/probe.js',
    });
    const found = (result?.messages ?? []).filter(
      (m) => m.ruleId === 'no-restricted-syntax',
    );
    expect(found).toHaveLength(1);
    expect(found[0]?.message).toMatch(/decimal strings/);
  });
});

// @vitest-environment node
import { fileURLToPath } from 'node:url';
import { ESLint } from 'eslint';
import { describe, expect, it } from 'vitest';
import {
  importRestrictions,
  restrictionRules,
} from '../../eslint.restrictions.js';

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

// A separate instance: JSX needs its parser option, and the import rule is
// scoped by folder.
const jsxEslint = new ESLint({
  overrideConfigFile: true,
  overrideConfig: [
    {
      files: ['**/*.tsx', '**/*.ts'],
      languageOptions: {
        sourceType: 'module',
        parserOptions: { ecmaFeatures: { jsx: true } },
      },
      rules: restrictionRules,
    },
    ...importRestrictions,
  ],
});

async function messagesAt(code: string, filePath: string) {
  const [result] = await jsxEslint.lintText(code, { filePath });
  return result?.messages ?? [];
}

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
    ['const s = "1"; +s', /decimal strings/],
    ['const s = "1"; ~~s', /decimal strings/],
    ['const N = Number; N("1")', /decimal strings/],
    ['const { parseFloat: p } = Number; p("1")', /decimal strings/],
    ['["1"].map(Number)', /decimal strings/],
    ['globalThis.Number("1")', /decimal strings/],
    ['window.parseFloat("1")', /decimal strings/],
    ['globalThis.parseFloat("1")', /decimal strings/],
    ['Number["parseFloat"]("1")', /decimal strings/],
    ['Number.call(null, "1")', /decimal strings/],
    ['Number.apply(null, ["1"])', /decimal strings/],
    ['const f = Number.bind(null); f("1")', /decimal strings/],
    ['localStorage.getItem("k")', /memory only/],
    ['sessionStorage.getItem("k")', /memory only/],
    ['window.localStorage', /memory only/],
    ['window.sessionStorage', /memory only/],
    ['globalThis.localStorage', /memory only/],
    ['indexedDB.open("db")', /memory only/],
    ['window.indexedDB', /memory only/],
    ['globalThis.indexedDB', /memory only/],
    ['document.cookie', /memory only/],
  ])('rejects %s with its reason', async (code, reason) => {
    const found = await messages(code);
    expect(found.length).toBeGreaterThan(0);
    for (const message of found) expect(message.message).toMatch(reason);
  });

  it.each([
    'Number.isInteger(1)',
    'Number.parseInt("1", 10)',
    'parseInt("1", 10)',
    'Number.MAX_SAFE_INTEGER',
    'String(1)',
    'const n = 1; `${n}`',
    'const n = 1; n.toString()',
    'let x = 0; x += 1',
    'const y = -1',
    'const a = 1; const b = 2; a + b',
  ])('allows %s', async (code) => {
    expect(await messages(code)).toHaveLength(0);
  });
});

describe('untrusted text', () => {
  it.each([
    "export const a = <div dangerouslySetInnerHTML={{ __html: 'x' }} />;",
    "export const b = { dangerouslySetInnerHTML: { __html: 'x' } };",
    "export const c = { 'dangerouslySetInnerHTML': { __html: 'x' } };",
    'export const d = (el, t) => { el.innerHTML = t; };',
    'export const e = (el, t) => { el.outerHTML = t; };',
  ])('rejects %s with its reason', async (code) => {
    const found = await messagesAt(code, 'src/features/feed/sample.tsx');
    expect(found).toHaveLength(1);
    expect(found[0]?.message).toMatch(/untrusted/);
  });

  it('allows a text child', async () => {
    const found = await messagesAt(
      'export const a = (t) => <p>{t}</p>;',
      'src/features/feed/sample.tsx',
    );
    expect(found).toHaveLength(0);
  });
});

describe('in-season screens and the review folder', () => {
  const review =
    "import { x } from '../review/reviewLabels.ts';\nexport { x };\n";

  it.each([
    'src/features/feed/sample.ts',
    'src/features/ranking/sample.ts',
    'src/features/feed/deep/sample.ts',
  ])('rejects an import of the review folder from %s', async (filePath) => {
    const found = await messagesAt(review, filePath);
    expect(found).toHaveLength(1);
    expect(found[0]?.ruleId).toBe('no-restricted-imports');
    expect(found[0]?.message).toMatch(/Review-only/);
  });

  it('lets the review folder import its own files', async () => {
    const found = await messagesAt(
      "import { x } from './reviewLabels.ts';\nexport { x };\n",
      'src/features/review/sample.ts',
    );
    expect(found).toHaveLength(0);
  });

  it('allows the feed folder to import other folders', async () => {
    const found = await messagesAt(
      "import { x } from '../seasons/labels.ts';\nexport { x };\n",
      'src/features/feed/sample.ts',
    );
    expect(found).toHaveLength(0);
  });
});

describe('real eslint.config.js', () => {
  it('ignores the generated schema file', async () => {
    const real = new ESLint({
      cwd: fileURLToPath(new URL('../../', import.meta.url)),
    });
    expect(await real.isPathIgnored('src/api/schema.d.ts')).toBe(true);
    expect(await real.isPathIgnored('src/api/client.ts')).toBe(false);
  });

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

  it.each([
    ['src/features/feed/probe.ts', true],
    ['src/features/ranking/probe.tsx', true],
    ['src/features/review/probe.ts', false],
  ])('import restriction for %s is %s', async (file, expected) => {
    const config = (await real.calculateConfigForFile(file)) as {
      rules?: Record<string, unknown[] | number | string>;
    };
    const setting = config.rules?.['no-restricted-imports'];
    const severity = Array.isArray(setting) ? setting[0] : setting;
    expect([2, 'error'].includes(severity as number | string)).toBe(expected);
  });

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

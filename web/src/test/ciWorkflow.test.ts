// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const workflow = readFileSync(
  new URL('../../../.github/workflows/web.yml', import.meta.url),
  'utf8',
);
const pkg = JSON.parse(
  readFileSync(new URL('../../package.json', import.meta.url), 'utf8'),
) as { scripts: Record<string, string> };

const PATHS = `paths: ['web/**', 'docs/api/openapi.yaml', '.github/workflows/web.yml']`;

const PINNED_USES =
  /^\s*-?\s*uses:\s*[\w.-]+\/[\w.-]+@[0-9a-f]{40}\s+#\s*v\d+\.\d+\.\d+$/;

function isPinned(line: string): boolean {
  return PINNED_USES.test(line);
}

function hasWritePermission(text: string): boolean {
  return /^\s*[\w-]+:\s*write(-all)?\s*$/m.test(text);
}

function usesLines(text: string): string[] {
  return text.split('\n').filter((line) => /^\s*-?\s*uses:/.test(line));
}

function block(text: string, header: string): string {
  const lines = text.split('\n');
  const start = lines.findIndex((line) => line === header);
  expect(start, header).toBeGreaterThanOrEqual(0);
  const body: string[] = [];
  for (const line of lines.slice(start + 1)) {
    if (line !== '' && !line.startsWith(' ')) break;
    body.push(line);
  }
  return body.join('\n');
}

function runCommands(text: string): string[] {
  return [...text.matchAll(/^[ \t]*-?[ \t]*run:[ \t]+(.+)$/gm)].map(
    (m) => m[1] ?? '',
  );
}

describe('web workflow triggers', () => {
  it('runs on pull_request and push to main for the web paths only', () => {
    const on = block(workflow, 'on:');
    const pr = block(on.replace(/^ {2}/gm, ''), 'pull_request:');
    const push = block(on.replace(/^ {2}/gm, ''), 'push:');
    expect(pr).toContain(PATHS);
    expect(pr).toContain('types: [opened, synchronize, reopened]');
    expect(push).toContain(PATHS);
    expect(push).toContain('branches: [main]');
    expect(on).toContain('workflow_dispatch:');
  });

  it('never uses pull_request_target', () => {
    expect(workflow).not.toContain('pull_request_target');
  });
});

describe('web workflow permissions and secrets', () => {
  it('grants contents: read and nothing else', () => {
    expect(block(workflow, 'permissions:').trim()).toBe('contents: read');
    expect(hasWritePermission(workflow)).toBe(false);
  });

  it('uses no secrets and no untrusted event text in scripts', () => {
    expect(workflow).not.toContain('secrets.');
    expect(workflow).not.toMatch(
      /\$\{\{\s*github\.event\.pull_request\.(title|body|head\.ref)/,
    );
    expect(workflow).not.toMatch(/github\.head_ref/);
  });
});

describe('web workflow actions', () => {
  it('pins every action to a full commit SHA with a version comment', () => {
    const lines = usesLines(workflow);
    expect(lines).toHaveLength(2);
    for (const line of lines) expect(isPinned(line), line).toBe(true);
  });

  it('uses only the two first-party actions the job needs', () => {
    const names = usesLines(workflow).map((line) =>
      line.replace(/^\s*-?\s*uses:\s*/, '').replace(/@.*$/, ''),
    );
    expect(names.sort()).toEqual(['actions/checkout', 'actions/setup-node']);
  });
});

describe('web workflow job', () => {
  it('has the pinned runner, timeout, directory and inputs', () => {
    expect(workflow).toMatch(/^ {4}runs-on: ubuntu-24\.04$/m);
    const timeout = /^ {4}timeout-minutes: (\d+)$/m.exec(workflow);
    expect(Number.parseInt(timeout?.[1] ?? '99', 10)).toBeLessThanOrEqual(10);
    expect(workflow).toMatch(/^ {8}working-directory: web$/m);
    expect(workflow).toContain('persist-credentials: false');
    expect(workflow).toContain('node-version-file: web/.nvmrc');
    expect(workflow).toContain('cache-dependency-path: web/package-lock.json');
  });

  it('cancels superseded runs of the same ref', () => {
    expect(workflow).toContain('group: web-${{ github.ref }}');
    expect(workflow).toContain('cancel-in-progress: true');
  });
});

describe('local and CI commands agree', () => {
  const steps = runCommands(workflow);

  it('installs with npm ci before any stage', () => {
    expect(steps[0]).toBe('npm ci');
  });

  it('checks the generated types before the stages', () => {
    const drift = workflow.indexOf('npm run gen:api');
    expect(drift).toBeGreaterThan(workflow.indexOf('npm ci'));
    expect(drift).toBeLessThan(workflow.indexOf('npm run lint'));
    expect(workflow).toContain('git diff --exit-code -- src/api/schema.d.ts');
  });

  it('runs each verify stage as its own step, in verify order', () => {
    const stages = [
      ...(pkg.scripts.verify ?? '').matchAll(/npm run (\S+)/g),
    ].map((m) => m[1] ?? '');
    expect(stages).toEqual([
      'lint',
      'format:check',
      'typecheck',
      'test',
      'build',
    ]);
    const expected = stages.map((s) =>
      s === 'test' ? 'npm test' : `npm run ${s}`,
    );
    const ci = steps.filter((s) => expected.includes(s));
    expect(ci).toEqual(expected);
  });

  it('never runs the browser end-to-end suite', () => {
    expect(workflow).not.toMatch(/npm run e2e|playwright/);
  });
});

describe('workflow matchers', () => {
  it('rejects a tag, a short SHA and a missing version comment', () => {
    const sha = '3d3c42e5aac5ba805825da76410c181273ba90b1';
    expect(isPinned(`      - uses: actions/checkout@${sha} # v7.0.1`)).toBe(
      true,
    );
    expect(isPinned('      - uses: actions/checkout@v5')).toBe(false);
    expect(
      isPinned(`      - uses: actions/checkout@${sha.slice(1)} # v7.0.1`),
    ).toBe(false);
    expect(isPinned(`      - uses: actions/checkout@${sha}`)).toBe(false);
  });

  it('flags a write permission', () => {
    expect(hasWritePermission('permissions:\n  contents: write\n')).toBe(true);
    expect(hasWritePermission('permissions: write-all\n')).toBe(true);
    expect(hasWritePermission('permissions:\n  contents: read\n')).toBe(false);
  });
});

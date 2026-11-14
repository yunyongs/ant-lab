// @vitest-environment node
import { spawnSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { readE2eEnv } from '../../e2e/support/env.ts';

const repoRoot = new URL('../../../', import.meta.url);
const webRoot = new URL('../../', import.meta.url);
const script = readFileSync(new URL('scripts/web_e2e.sh', repoRoot), 'utf8');
const hasBash = spawnSync('bash', ['--version']).status === 0;

function dryRun(extra: Record<string, string> = {}) {
  const env: Record<string, string> = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (value !== undefined && !key.startsWith('ANTLAB_')) env[key] = value;
  }
  return spawnSync('bash', ['scripts/web_e2e.sh', '--dry-run'], {
    cwd: repoRoot,
    env: { ...env, ...extra },
    encoding: 'utf8',
  });
}

describe('scripts/web_e2e.sh text', () => {
  it('fails on errors and always cleans up', () => {
    // Line-anchored so a commented-out line does not satisfy the check.
    expect(script).toMatch(/^set -euo pipefail$/m);
    expect(script).toMatch(/^umask 077$/m);
    expect(script).toMatch(/^trap cleanup EXIT$/m);
    expect(script).toMatch(/^\s*dc down -v --remove-orphans --rmi local/m);
  });

  it('guards the project name', () => {
    expect(script).toContain('^antlab-e2e(-[a-z0-9]+)?');
  });

  it('removes volumes only through the named project', () => {
    expect(script).toMatch(/-p "\$project"/);
    for (const line of script.split('\n')) {
      if (!/\bdown\b.*\s-v\b/.test(line)) continue;
      const trimmed = line.trim();
      const ok =
        trimmed.startsWith('dc down') ||
        (trimmed.startsWith('printf') && trimmed.includes('-p'));
      expect(ok, line).toBe(true);
    }
    expect(script).not.toMatch(/^\s*docker compose\s+down/m);
  });

  it('never uses the play overlay or the owner stack ports as defaults', () => {
    expect(script).not.toContain('compose.play.yaml');
    expect(script).not.toMatch(/\b5432:/);
    expect(script).not.toMatch(/\b8080:/);
  });

  it('keeps the signing key in the temp env file only', () => {
    expect(script).toMatch(/secrets\.token_bytes/);
    expect(script).toMatch(
      /printf 'ANTLAB_JWT_SECRET=%s\\n' "\$jwt_key" >> "\$env_file"/,
    );
    for (const line of script.split('\n')) {
      if (!line.includes('$jwt_key')) continue;
      const printsToFile = line.includes('>> "$env_file"');
      const assignsIt = /^\s*jwt_key=/.test(line);
      expect(printsToFile || assignsIt, line).toBe(true);
    }
    expect(script).not.toMatch(/echo[^\n]*jwt/);
  });
});

describe.skipIf(!hasBash)('scripts/web_e2e.sh --dry-run', () => {
  it('prints the defaults without calling docker or psql', () => {
    const result = dryRun();
    expect(result.status).toBe(0);
    expect(result.stdout).toContain(
      'project=antlab-e2e db_port=55476 api_port=18096 web_port=5196',
    );
  });

  it('refuses a project name outside the guard', () => {
    const result = dryRun({ ANTLAB_E2E_PROJECT: 'antlab' });
    expect(result.status).toBe(2);
    expect(result.stderr).toContain('ANTLAB_E2E_PROJECT must match');
  });

  it.each([
    ['ANTLAB_E2E_API_PORT', '8080'],
    ['ANTLAB_E2E_DB_PORT', '5432'],
    ['ANTLAB_E2E_WEB_PORT', '5173'],
  ])('refuses %s=%s', (name, value) => {
    const result = dryRun({ [name]: value });
    expect(result.status).toBe(2);
    expect(result.stderr).toContain(`${name} must not be ${value}`);
  });
});

describe('end-to-end environment', () => {
  it('requires ANTLAB_API_PORT with a one-line message', () => {
    expect(() => readE2eEnv({}, '/w')).toThrow(/^ANTLAB_API_PORT is required/);
  });

  it('rejects the development server port and bad ports', () => {
    expect(() =>
      readE2eEnv(
        { ANTLAB_API_PORT: '18096', ANTLAB_E2E_WEB_PORT: '5173' },
        '/w',
      ),
    ).toThrow(/must not be 5173/);
    expect(() => readE2eEnv({ ANTLAB_API_PORT: '0' }, '/w')).toThrow(
      /from 1 to 65535/,
    );
  });

  it('defaults the web port and resolves the output folder against web/', () => {
    expect(readE2eEnv({ ANTLAB_API_PORT: '18096' }, '/w')).toEqual({
      apiPort: 18096,
      webPort: 5196,
      outDir: '/w/test-results',
    });
  });
});

describe('playwright config', () => {
  const saved = { ...process.env };
  afterEach(() => {
    process.env = { ...saved };
    vi.resetModules();
  });

  it('plays two Chromium projects at the two widths without retries', async () => {
    process.env.ANTLAB_API_PORT = '18096';
    const { default: config } = await import('../../playwright.config.ts');
    expect(config.retries).toBe(0);
    expect(config.workers).toBe(1);
    expect(config.testMatch).toBe('**/*.e2e.ts');
    const projects = (config.projects ?? []).map((project) => ({
      name: project.name,
      browser: project.use?.browserName,
      viewport: project.use?.viewport,
    }));
    expect(projects).toEqual([
      {
        name: 'mobile',
        browser: 'chromium',
        viewport: { width: 375, height: 812 },
      },
      {
        name: 'desktop',
        browser: 'chromium',
        viewport: { width: 1280, height: 800 },
      },
    ]);
  });

  it('refuses to load without ANTLAB_API_PORT', async () => {
    delete process.env.ANTLAB_API_PORT;
    await expect(import('../../playwright.config.ts')).rejects.toThrow(
      /ANTLAB_API_PORT is required/,
    );
  });
});

describe('end-to-end sources', () => {
  const read = (name: string) => readFileSync(new URL(name, webRoot), 'utf8');

  it('takes the date pattern from one file', () => {
    expect(read('src/test/expectNoDatePattern.ts')).toContain(
      "from './datePattern.ts'",
    );
    expect(read('e2e/support/steps.ts')).toContain(
      "from '../../src/test/datePattern.ts'",
    );
    for (const file of [
      'src/test/expectNoDatePattern.ts',
      'e2e/support/steps.ts',
    ]) {
      expect(read(file)).not.toMatch(/DATE_PATTERN\s*=/);
    }
  });

  it('keeps browser tests out of the Vitest run', () => {
    const names = readdirSync(new URL('e2e/', webRoot), {
      recursive: true,
      encoding: 'utf8',
    }).filter((name) => name.endsWith('.ts'));
    expect(names.length).toBeGreaterThan(0);
    for (const name of names) {
      expect(name, name).not.toMatch(/\.(test|spec)\.ts$/);
    }
    expect(names).toContain('season.e2e.ts');
  });
});

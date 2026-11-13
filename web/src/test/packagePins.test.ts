// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (name: string) =>
  readFileSync(new URL(`../../${name}`, import.meta.url), 'utf8');

const pkg = JSON.parse(read('package.json')) as {
  dependencies: Record<string, string>;
  devDependencies: Record<string, string>;
  engines: { node: string };
};

describe('package pins', () => {
  it('pins every dependency to an exact version', () => {
    const all = { ...pkg.dependencies, ...pkg.devDependencies };
    for (const [name, version] of Object.entries(all)) {
      expect(version, name).toMatch(/^\d+\.\d+\.\d+$/);
    }
  });

  it('keeps .nvmrc, engines.node and .npmrc consistent', () => {
    const major = read('.nvmrc').trim();
    expect(major).toMatch(/^\d+$/);
    expect(pkg.engines.node).toBe(
      `>=${major} <${String(Number.parseInt(major, 10) + 1)}`,
    );
    const npmrc = read('.npmrc');
    expect(npmrc).toContain('engine-strict=true');
    expect(npmrc).toContain('save-exact=true');
  });
});

describe('prettier ignore', () => {
  it('lists the generated schema file', () => {
    expect(read('.prettierignore').split('\n')).toContain(
      'src/api/schema.d.ts',
    );
  });
});

describe('app tsconfig', () => {
  const app = read('tsconfig.app.json');

  it('keeps JavaScript and Node typings out of the browser project', () => {
    expect(app).not.toMatch(/"allowJs"\s*:\s*true/);
    expect(app).not.toMatch(/"types"\s*:\s*\[[^\]]*"node"/);
  });

  it('leaves every Node-environment test to tsconfig.node.json', () => {
    const nodeConfig = read('tsconfig.node.json');
    for (const name of readdirSync(new URL('./', import.meta.url))) {
      if (!name.endsWith('.test.ts')) continue;
      const source = readFileSync(new URL(name, import.meta.url), 'utf8');
      if (!source.startsWith('// @vitest-environment node')) continue;
      expect(app, name).toContain(`src/test/${name}`);
      expect(nodeConfig, name).toContain(`src/test/${name}`);
    }
  });
});

describe('client env', () => {
  const webRoot = new URL('../../', import.meta.url);

  function walk(dir: URL): URL[] {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
      entry.isDirectory()
        ? walk(new URL(`${entry.name}/`, dir))
        : [new URL(entry.name, dir)],
    );
  }

  it('has no .env files in web/', () => {
    expect(readdirSync(webRoot).filter((name) => /^\.env/.test(name))).toEqual(
      [],
    );
  });

  it('uses no VITE_ client variables', () => {
    const files = [
      ...walk(new URL('src/', webRoot)),
      ...['vite.config.ts', 'vite.proxy.ts', 'index.html'].map(
        (name) => new URL(name, webRoot),
      ),
    ].filter((file) => !file.pathname.endsWith('/packagePins.test.ts'));
    for (const file of files) {
      expect(readFileSync(file, 'utf8'), file.pathname).not.toMatch(/VITE_/);
    }
  });

  it('keeps web/src to TypeScript and CSS files', () => {
    const odd = walk(new URL('src/', webRoot))
      .map((file) => file.pathname.slice(webRoot.pathname.length))
      .filter((name) => !/\.(tsx?|css)$/.test(name));
    expect(odd).toEqual([]);
  });
});

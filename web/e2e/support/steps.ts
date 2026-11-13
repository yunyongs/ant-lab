import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { DATE_PATTERN } from '../../src/test/datePattern.ts';
import { readE2eEnv } from './env.ts';

const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

interface AxeLine {
  screen: string;
  id: string;
  impact: string;
  nodes: number;
}

export interface Run {
  page: Page;
  project: string;
  dir: string;
  count: number;
  screens: Set<string>;
  axeLines: AxeLine[];
}

export function startRun(page: Page, project: string): Run {
  const { outDir } = readE2eEnv(process.env, process.cwd());
  return {
    page,
    project,
    dir: join(outDir, project),
    count: 0,
    screens: new Set(),
    axeLines: [],
  };
}

// The body only: the dev server's own script and style tags are not the app's.
async function bodyText(page: Page): Promise<string> {
  return page.evaluate(
    () => `${document.body.innerText}\n${document.body.innerHTML}`,
  );
}

export async function expectNoOverflow(
  page: Page,
  name: string,
): Promise<void> {
  const widths = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  expect(
    widths.scroll,
    `horizontal overflow at "${name}": scrollWidth ${String(widths.scroll)} > clientWidth ${String(widths.client)}`,
  ).toBeLessThanOrEqual(widths.client);
}

export async function expectNoDate(page: Page, name: string): Promise<void> {
  const text = await bodyText(page);
  expect(
    DATE_PATTERN.test(text),
    `a real date reached the screen before the season ended, at "${name}"`,
  ).toBe(false);
}

export async function expectDatePresent(
  page: Page,
  name: string,
): Promise<void> {
  const text = await bodyText(page);
  expect(
    DATE_PATTERN.test(text),
    `no real date on the review screen at "${name}": the reveal does not work`,
  ).toBe(true);
}

async function scanAxe(run: Run, screen: string): Promise<void> {
  if (run.screens.has(screen)) return;
  run.screens.add(screen);
  const result = await new AxeBuilder({ page: run.page })
    .withTags(AXE_TAGS)
    .analyze();
  const critical: string[] = [];
  for (const violation of result.violations) {
    const impact = violation.impact ?? 'minor';
    run.axeLines.push({
      screen,
      id: violation.id,
      impact,
      nodes: violation.nodes.length,
    });
    if (impact === 'critical') critical.push(violation.id);
    if (impact === 'serious') {
      console.log(
        `axe serious [${run.project}/${screen}] ${violation.id} (${String(violation.nodes.length)} nodes)`,
      );
    }
  }
  expect(critical, `critical axe violations at "${screen}"`).toEqual([]);
}

export interface CheckOptions {
  // Run axe on the first visit of this screen.
  axe?: string;
  // `present` is the reverse check of the review screen; default is `none`.
  dates?: 'none' | 'present';
  // A light check skips the screenshot (the turn loop).
  screenshot?: boolean;
}

// One checkpoint = screenshot + overflow + date check (+ axe on a new screen).
export async function checkpoint(
  run: Run,
  name: string,
  options: CheckOptions = {},
): Promise<void> {
  const { axe, dates = 'none', screenshot = true } = options;
  await test.step(name, async () => {
    if (screenshot) {
      run.count += 1;
      const file = `${String(run.count).padStart(2, '0')}-${name}.png`;
      mkdirSync(run.dir, { recursive: true });
      await run.page.screenshot({ path: join(run.dir, file), fullPage: true });
    }
    await expectNoOverflow(run.page, name);
    if (dates === 'present') await expectDatePresent(run.page, name);
    else await expectNoDate(run.page, name);
    if (axe !== undefined) await scanAxe(run, axe);
  });
}

export function writeAxeSummary(run: Run): void {
  mkdirSync(run.dir, { recursive: true });
  writeFileSync(
    join(run.dir, 'axe-summary.json'),
    `${JSON.stringify(run.axeLines, null, 2)}\n`,
  );
}

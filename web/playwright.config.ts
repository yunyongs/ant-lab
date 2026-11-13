import { defineConfig } from '@playwright/test';
import { join } from 'node:path';
import { readE2eEnv } from './e2e/support/env.ts';

const { apiPort, webPort, outDir } = readE2eEnv(process.env, process.cwd());

export default defineConfig({
  testDir: 'e2e',
  testMatch: '**/*.e2e.ts',
  outputDir: join(outDir, 'artifacts'),
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 10_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: join(outDir, 'report'), open: 'never' }],
  ],
  use: {
    baseURL: `http://localhost:${String(webPort)}`,
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    reducedMotion: 'reduce',
    colorScheme: 'light',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
    screenshot: 'only-on-failure',
    // A trace holds short-lived tokens: keep it for failures only and never attach it anywhere.
    trace: 'retain-on-failure',
    video: 'off',
  },
  projects: [
    {
      name: 'mobile',
      use: {
        browserName: 'chromium',
        viewport: { width: 375, height: 812 },
        isMobile: true,
        hasTouch: true,
        deviceScaleFactor: 2,
      },
    },
    {
      name: 'desktop',
      use: { browserName: 'chromium', viewport: { width: 1280, height: 800 } },
    },
  ],
  webServer: {
    command: `npm run dev -- --port ${String(webPort)}`,
    url: `http://localhost:${String(webPort)}`,
    reuseExistingServer: false,
    timeout: 60_000,
    env: { ANTLAB_API_PORT: String(apiPort) },
    stdout: 'pipe',
  },
});

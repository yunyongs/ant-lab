import { expect, test, type Locator, type Page } from '@playwright/test';
import type { Guards } from './guards.ts';
import type { Member } from './member.ts';
import {
  checkpoint,
  expectNoOverflow,
  startRun,
  writeAxeSummary,
  type Run,
} from './steps.ts';

export const mainNav = (page: Page): Locator =>
  page.getByRole('navigation', { name: '주 메뉴' });

export const menuButton = (page: Page): Locator =>
  page.getByRole('button', { name: '메뉴' });

export const sheetOf = (page: Page): Locator => page.getByRole('dialog');

// Cold start: the visitor is sent to the login form.
export async function coldStartToLogin(page: Page, run: Run): Promise<void> {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
  await checkpoint(run, 'login', { axe: 'login' });
}

async function fillSignup(page: Page, member: Member): Promise<void> {
  await page.getByLabel('이메일').fill(member.email);
  await page.getByLabel('표시 이름').fill(member.name);
  await page.getByLabel('비밀번호', { exact: true }).fill(member.password);
}

async function submitSignup(page: Page, guards: Guards): Promise<void> {
  await page.getByRole('button', { name: '가입하고 로그인' }).click();
  await expect(page.getByRole('heading', { name: '내 시즌' })).toBeVisible();
  guards.setLoggedIn(true);
}

// Sign up from the login form (the app logs in right after).
export async function signUpFromLogin(
  page: Page,
  run: Run,
  guards: Guards,
  member: Member,
): Promise<void> {
  await page.getByRole('link', { name: '처음이세요? 계정 만들기' }).click();
  await expect(
    page.getByRole('heading', { name: '계정 만들기' }),
  ).toBeVisible();
  await fillSignup(page, member);
  await checkpoint(run, 'signup-form', { axe: 'signup' });
  await submitSignup(page, guards);
}

// Sign up by address, without the login form.
export async function signUpDirect(
  page: Page,
  guards: Guards,
  member: Member,
): Promise<void> {
  await page.goto('/signup');
  await fillSignup(page, member);
  await submitSignup(page, guards);
}

// Tokens live in memory only; the refresh cookie is HttpOnly.
export async function expectTokensInMemoryOnly(page: Page): Promise<void> {
  const storage = await page.evaluate(() => ({
    local: localStorage.length,
    session: sessionStorage.length,
    cookie: document.cookie,
  }));
  expect(storage.local, 'localStorage entries').toBe(0);
  expect(storage.session, 'sessionStorage entries').toBe(0);
  expect(storage.cookie, 'script-readable cookies').not.toContain(
    'antlab_refresh',
  );
  const cookies = await page.context().cookies();
  expect(
    cookies.some((cookie) => cookie.httpOnly && cookie.secure),
    'an HttpOnly Secure refresh cookie is set',
  ).toBe(true);
}

// Open the header menu; its items are buttons inside the page.
export async function openMenu(page: Page): Promise<void> {
  const button = menuButton(page);
  if ((await button.getAttribute('aria-expanded')) !== 'true')
    await button.click();
  await expect(button).toHaveAttribute('aria-expanded', 'true');
}

// The open menu has its own screenshot and axe scan (own folder, own counter).
async function checkOpenMenu(page: Page): Promise<void> {
  const run = startRun(page, `${test.info().project.name}-menu`);
  await openMenu(page);
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
  // Logout runs after the review screen, where real dates are shown.
  await checkpoint(run, 'menu-open', { axe: 'menu', dates: 'present' });
  // Any violation fails here, not only critical ones.
  expect(run.axeLines, 'axe violations in the open menu').toEqual([]);
  writeAxeSummary(run);
}

// Logout, then the bearer probe and the guarded season address.
export async function logoutAndProbe(
  page: Page,
  guards: Guards,
  seasonId: string,
): Promise<void> {
  await checkOpenMenu(page);
  guards.setLoggedIn(false);
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
  await expectNoOverflow(page, 'logged-out');
  expect(guards.bearerRequests(), 'season requests seen').toBeGreaterThan(0);
  const probe = await page.request.get('/api/v1/seasons');
  expect(probe.status()).toBe(401);
  expect(probe.headers()['content-type']).toContain('application/problem+json');
  expect(((await probe.json()) as { code?: string }).code).toBe(
    'UNAUTHENTICATED',
  );
  await page.goto(`/seasons/${seasonId}`);
  await expect(page).toHaveURL(/\/login/);
}

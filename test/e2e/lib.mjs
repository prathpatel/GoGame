// Shared helpers for the browser tests. See README.md in this folder.
import { createRequire } from 'module';
import fs from 'fs';
import path from 'path';

const require = createRequire(import.meta.url);
let playwright;
try {
  playwright = require('playwright');
} catch {
  console.error('Playwright is missing. In test/e2e, run: npm install && npx playwright install chromium');
  process.exit(2);
}
export const { chromium, devices } = playwright;

export const base = (process.env.GOGAME_URL || 'http://localhost:9663').replace(/\/$/, '');
export const out = path.resolve(process.env.GOGAME_E2E_OUT || new URL('./output', import.meta.url).pathname);
fs.mkdirSync(out, { recursive: true });

let failures = 0;
export const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
  if (!ok) failures++;
};
export const done = async browser => {
  await browser?.close();
  console.log(failures ? `\n${failures} check(s) failed. Screenshots: ${out}` : `\nAll checks passed. Screenshots: ${out}`);
  process.exit(failures ? 1 : 0);
};

/** A player with its own cookies: a separate guest, or a separate account after signup(). */
export const newPlayer = async (browser, name, options = {}) => {
  const context = await browser.newContext({ viewport: { width: 1300, height: 900 }, ...options });
  const page = await context.newPage();
  page.on('pageerror', e => console.log(`  [${name}] page error: ${e.message}`));
  page.name = name;
  return page;
};

/** Creates and signs in a new account (the local dev server does not send emails). */
export const signup = async page => {
  const user = 'test' + Math.random().toString(36).slice(2, 9);
  await page.goto(base + '/signup', { waitUntil: 'networkidle' });
  await page.fill('input[name=username]', user);
  await page.fill('input[name=password]', 'e2e-password-' + user);
  // the email domain must accept mail (MX record): example.com is refused
  await page.fill('input[name=email]', `${user}@gmail.com`);
  await page.$$eval('input[type=checkbox][name^=agreement]', cs => cs.forEach(c => (c.checked = true)));
  await page.locator('#signup-form button.submit').click();
  await page.waitForLoadState('networkidle');
  return user;
};

export const home = page => page.goto(`${base}/?r=${Math.random()}`, { waitUntil: 'networkidle' });

/** Opens a setup dialog: 'hook' (lobby game), 'friend' or 'ai'. */
export const openSetup = async (page, kind, { size = 9, side, level } = {}) => {
  await page.click(`.lobby__start__button--${kind}`);
  await page.waitForSelector('.game-setup');
  await page.click(`button.go-size__button:has-text("${size}×${size}")`);
  if (level) await page.locator('dialog label').filter({ hasText: new RegExp(`^${level}$`) }).first().click();
  if (side) await page.locator(`dialog >> text=${side}`).first().click();
};
export const submitSetup = page => page.locator('dialog .footer button').first().evaluate(b => b.click());

export const button = (page, text) =>
  page.locator('.go-controls button, .go-actions button').filter({ hasText: new RegExp(`^${text}$`) }).first();

export const status = page => page.textContent('.go-status').catch(() => '');
export const moveCount = page => page.$$eval('.go-moves move', m => m.length).catch(() => 0);
export const myColor = page =>
  page.evaluate(() => {
    // your own player box is the one under the board list (the last one)
    const players = document.querySelectorAll('.go-player');
    const me = players[players.length - 1]?.querySelector('.go-player__stone');
    return me?.classList.contains('black') ? 'black' : me?.classList.contains('white') ? 'white' : null;
  });

export const waitFor = async (fn, { timeout = 20000, every = 250 } = {}) => {
  for (let t = 0; t < timeout; t += every) {
    try {
      if (await fn()) return true;
    } catch {
      // the page is navigating: try again
    }
    await new Promise(r => setTimeout(r, every));
  }
  return false;
};
export const waitMyTurn = page => waitFor(async () => (await status(page)).includes('Your turn'));

/** Clicks (or taps) the intersection x, y (0-based from the top left, like SGF "aa"). */
export const play = async (page, x, y, { tap = false } = {}) => {
  const svg = await page.$('svg.goban');
  const size = await svg.evaluate(el => +el.getAttribute('data-size') || el.querySelectorAll('line.goban__line').length / 2);
  const box = await svg.boundingBox();
  const full = 18 + 10 * (size - 1);
  const px = box.x + ((9 + x * 10) / full) * box.width;
  const py = box.y + ((9 + y * 10) / full) * box.height;
  if (tap) await page.touchscreen.tap(px, py);
  else await page.mouse.click(px, py);
};

/** The horizontal overflow of the page, in pixels: 0 when nothing scrolls sideways. */
export const overflow = page => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);

export const shot = (page, name) => page.screenshot({ path: path.join(out, `${name}.png`), fullPage: true });

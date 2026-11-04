// Phone and tablet layouts, with Playwright's device emulation (screen size, touch, user agent):
//   - every page fits the screen (no sideways scrolling)
//   - a game against the computer, played by tapping, on 9×9 and 19×19
//   - a game between a phone and a desktop browser, as two independent players
// Usage: node mobile.mjs   (see README.md). Screenshots go to ./output.
import {
  base, button, check, chromium, devices, done, home, moveCount, newPlayer, openSetup, overflow, play,
  shot, submitSetup, waitFor, waitMyTurn,
} from './lib.mjs';

const browser = await chromium.launch();
const phones = ['iPhone 13', 'Pixel 7', 'iPad Mini'];

for (const device of phones) {
  const tag = device.replace(/\s+/g, '-');
  const page = await newPlayer(browser, device, devices[device]);
  const width = page.viewportSize().width;

  for (const path of ['/', '/games', '/faq', '/about', '/player', '/signup']) {
    await page.goto(base + path, { waitUntil: 'networkidle' });
    check(`${device}: ${path} fits the screen`, (await overflow(page)) <= 0, `${await overflow(page)}px too wide`);
  }
  await home(page);
  await shot(page, `${tag}-home`);

  for (const size of [9, 19]) {
    await home(page);
    await openSetup(page, 'ai', { size, side: 'Black', level: 1 });
    const dialog = await page.locator('dialog').boundingBox();
    if (size === 9) {
      check(`${device}: the setup dialog fits`, dialog.x >= 0 && dialog.x + dialog.width <= width + 1);
      await shot(page, `${tag}-setup`);
    }
    await submitSetup(page);
    await page.waitForSelector('svg.goban', { timeout: 20000 });
    const board = await page.locator('svg.goban').boundingBox();
    check(`${device}: the ${size}×${size} board fits the width`, board.width <= width + 1, `${Math.round(board.width)}px`);
    await waitMyTurn(page);
    const c = Math.floor(size / 2);
    await play(page, c, c, { tap: true });
    const replied = await waitFor(async () => (await moveCount(page)) >= 2, { timeout: 30000 });
    check(`${device}: a tap plays on ${size}×${size}, and the computer answers`, replied);
    check(`${device}: game controls visible`, await button(page, 'Pass').isVisible());
    check(`${device}: the game page fits the screen`, (await overflow(page)) <= 0);
    await shot(page, `${tag}-game${size}`);
  }
  await page.context().close();
}

// a phone (Pixel 7) against a desktop browser, through a challenge link
{
  const desk = await newPlayer(browser, 'desktop');
  const phone = await newPlayer(browser, 'phone', devices['Pixel 7']);
  await home(desk);
  await openSetup(desk, 'friend', { size: 9, side: 'Black' });
  await submitSetup(desk);
  await desk.waitForURL(/\/\w{8}$/, { timeout: 20000 });
  await phone.goto(desk.url(), { waitUntil: 'networkidle' });
  await phone.locator('form.accept button, button:has-text("Join the game"), button:has-text("Accept")').first().tap();
  await waitFor(() => phone.$('svg.goban').then(Boolean));
  await waitFor(() => desk.$('svg.goban').then(Boolean));
  for (let i = 0; i < 3; i++) {
    await waitMyTurn(desk);
    await play(desk, 2 + i, 2);
    await waitMyTurn(phone);
    await play(phone, 2 + i, 6, { tap: true });
  }
  const synced = await waitFor(async () => (await moveCount(desk)) === 6 && (await moveCount(phone)) === 6);
  check('phone vs desktop: six moves, both boards agree', synced);
  await shot(phone, 'phone-vs-desktop');
}

await done(browser);

# Browser tests

Two scripts drive real browsers with [Playwright](https://playwright.dev) against a running
GoGame (lila on port 9663, lila-ws on 9664):

| Script | What it checks |
| --- | --- |
| `multiplayer.mjs` | Two **independent players**, each in its own browser context, so each has its own cookies, like two devices. Two guests play through a challenge link: a capture, a reload mid-game (each keeps their side), a takeback, two passes and counting. Then two new accounts play a lobby game, resign, and rematch; a rated game resigned after two moves explains why there is no rematch. |
| `mobile.mjs` | **Phones and tablets**, with Playwright's device emulation: iPhone 13, Pixel 7 and iPad Mini (screen size, touch, user agent). Every page must fit without sideways scrolling; games against the computer on 9×9 and 19×19 are played by tapping; a phone plays a desktop browser. |

Each check prints `PASS` or `FAIL`. Screenshots go to `test/e2e/output/`. A script exits
with status 1 when a check fails.

## Running them

1. Start lila and lila-ws (see the main README).
2. Turn off rate limits, which refuse the many games the tests create in a few minutes.
   In `conf/application.conf`:

   ```hocon
   net.ratelimit = false
   ```

   and restart lila.
3. Install Playwright once:

   ```bash
   cd test/e2e && npm install && npx playwright install chromium
   ```

4. Run them:

   ```bash
   npm run multiplayer
   ```

   ```bash
   npm run mobile
   ```

`GOGAME_URL` points the tests at another address, e.g.
`GOGAME_URL=http://192.168.1.20:9663 npm test`.

The tests create accounts named `test…` and games in your local database.

## By hand

- **Independent players:** each player needs their own cookies. Use two browsers (Firefox
  and Chrome), or a normal and a private window, or two browser profiles. Two tabs of the
  same browser are the *same* guest. To test signed-in play, register one account in each.
- **Phones in the browser:** the device toolbar of the developer tools (Ctrl+Shift+M in
  Chrome and Firefox) emulates screen sizes and touch.
- **Real phones and tablets:** see "Playing from other devices" in the main README.

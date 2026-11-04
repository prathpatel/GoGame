// Two independent players, each in its own browser context (own cookies, like two devices):
//   1. two guests through a challenge link: capture, reload, takeback, passes and counting
//   2. two new accounts through the lobby: moves, resignation and rematch
// Usage: node multiplayer.mjs   (see README.md)
import {
  base, button, check, chromium, done, home, moveCount, myColor, newPlayer, openSetup, play, shot,
  signup, status, submitSetup, waitFor, waitMyTurn,
} from './lib.mjs';

const browser = await chromium.launch();

// ---- 1. guests, challenge link ----
{
  const A = await newPlayer(browser, 'guest A');
  const B = await newPlayer(browser, 'guest B');
  await home(A);
  await openSetup(A, 'friend', { size: 9, side: 'Black' });
  await submitSetup(A);
  await A.waitForURL(/\/\w{8}$/, { timeout: 20000 });
  const link = A.url();
  await B.goto(link, { waitUntil: 'networkidle' });
  await B.locator('form.accept button, button:has-text("Join the game"), button:has-text("Accept")').first().click();
  const started = (await waitFor(() => A.$('svg.goban').then(Boolean))) && (await waitFor(() => B.$('svg.goban').then(Boolean)));
  check('guests: challenge link starts a game for both', started, link.replace(base, ''));
  check('guests: each player has their own side', (await myColor(A)) === 'black' && (await myColor(B)) === 'white',
    `A ${await myColor(A)}, B ${await myColor(B)}`);

  // Black surrounds White's E5 stone and captures it
  const moves = [[A, 3, 4], [B, 4, 4], [A, 5, 4], [B, 0, 0], [A, 4, 3], [B, 8, 8], [A, 4, 5]];
  for (const [p, x, y] of moves) {
    if (!(await waitMyTurn(p))) break;
    await play(p, x, y);
    await p.waitForTimeout(300);
  }
  await waitFor(async () => (await moveCount(B)) === 7);
  const prisoners = await B.textContent('.go-player--black .go-player__prisoners').catch(() => '');
  check('guests: a capture shows on the other player\'s board', (await moveCount(B)) === 7 && prisoners.includes('1'), prisoners);

  // a reload keeps each player's identity
  await B.reload({ waitUntil: 'networkidle' });
  await B.waitForSelector('svg.goban');
  check('guests: after a reload White is still White, with controls',
    (await myColor(B)) === 'white' && (await button(B, 'Pass').count()) === 1);

  // White plays, asks for a takeback, Black accepts
  await waitMyTurn(B);
  await play(B, 7, 1);
  await waitFor(async () => (await moveCount(A)) === 8);
  await button(B, 'Takeback').click();
  const offered = await waitFor(() => button(A, 'Accept').count().then(n => n > 0), { timeout: 10000 });
  if (offered) await button(A, 'Accept').click();
  const undone = await waitFor(async () => (await moveCount(A)) === 7 && (await moveCount(B)) === 7, { timeout: 10000 });
  check('guests: takeback offered and accepted', offered && undone);

  // both pass, both accept the score
  await waitMyTurn(B);
  await button(B, 'Pass').click();
  await waitMyTurn(A);
  await button(A, 'Pass').click();
  const counting = await waitFor(() => button(A, 'Accept score').count().then(n => n > 0));
  check('guests: two passes start counting', counting);
  await shot(A, 'guests-counting');
  await button(A, 'Accept score').click();
  await waitFor(() => button(B, 'Accept score').count().then(n => n > 0));
  await button(B, 'Accept score').click();
  const finished = await waitFor(async () => (await A.$('.go-status.result')) && (await B.$('.go-status.result')));
  const [ra, rb] = [await status(A), await status(B)];
  check('guests: both see the same result', finished && ra === rb, ra);
  await shot(A, 'guests-result');
  await A.context().close();
  await B.context().close();
}

// ---- 2. accounts, lobby game, resignation, rematch ----
{
  const A = await newPlayer(browser, 'account A');
  const B = await newPlayer(browser, 'account B');
  const [ua, ub] = [await signup(A), await signup(B)];
  check('accounts: two new accounts', !!(await A.$('#user_tag')) && !!(await B.$('#user_tag')), `${ua}, ${ub}`);
  await home(A);
  await home(B);
  await B.locator('[role=tab]').filter({ hasText: /^Lobby$/ }).first().click();
  await openSetup(A, 'hook', { size: 9 });
  await submitSetup(A);
  const listed = await waitFor(() => B.$('.hooks__list tr.hook.join').then(Boolean), { timeout: 10000 });
  check('accounts: the lobby game appears for the other player', listed);
  await B.waitForTimeout(700); // the list ignores clicks for a moment after it changes
  await B.click('.hooks__list tr.hook.join');
  const started = (await waitFor(() => A.$('svg.goban').then(Boolean))) && (await waitFor(() => B.$('svg.goban').then(Boolean)));
  const sees = async (p, name) => (await p.textContent('.go-table')).includes(name);
  check('accounts: game started, each sees the other\'s name', started && (await sees(A, ub)) && (await sees(B, ua)));

  // six moves each (whoever is Black starts), then A resigns. A rated game that ends in fewer
  // than 10 moves counts as possible rating manipulation, and offers no rematch.
  const [black, white] = (await myColor(A)) === 'black' ? [A, B] : [B, A];
  const blacks = [[2, 2], [2, 6], [6, 2], [4, 4], [3, 3], [5, 5]];
  const whites = [[6, 6], [6, 4], [4, 6], [4, 2], [5, 3], [3, 5]];
  for (let i = 0; i < 6; i++) {
    await waitMyTurn(black);
    await play(black, ...blacks[i]);
    await waitMyTurn(white);
    await play(white, ...whites[i]);
  }
  await waitFor(async () => (await moveCount(A)) === 12);
  await button(A, 'Resign').click();
  await button(A, 'Confirm resign').click();
  const resigned = await waitFor(async () => (await A.$('.go-status.result')) && (await B.$('.go-status.result')));
  check('accounts: resignation ends the game for both', resigned, await status(B));

  // rematch: B offers, A accepts, both land in the new game as players
  const firstA = A.url();
  await button(B, 'Rematch').click();
  const offer = await waitFor(() => button(A, 'Accept').count().then(n => n > 0), { timeout: 10000 });
  if (offer) await button(A, 'Accept').click();
  const moved = await waitFor(() => A.url() !== firstA && B.url() !== firstA, { timeout: 15000 });
  await waitFor(() => A.$('svg.goban').then(Boolean));
  await waitFor(() => B.$('svg.goban').then(Boolean));
  const playable = (await button(A, 'Pass').count()) === 1 && (await button(B, 'Pass').count()) === 1;
  check('accounts: rematch offered, accepted, both are players in the new game', offer && moved && playable);
  await shot(B, 'accounts-rematch');

  // a rated game resigned after two moves offers no rematch, and says why
  const [black2, white2] = (await myColor(A)) === 'black' ? [A, B] : [B, A];
  await waitMyTurn(black2);
  await play(black2, 4, 4);
  await waitMyTurn(white2);
  await play(white2, 2, 2);
  await waitFor(async () => (await moveCount(A)) === 2);
  await button(A, 'Resign').click();
  await button(A, 'Confirm resign').click();
  const note = p => waitFor(async () => (await p.textContent('.go-table')).includes('No rematch'), { timeout: 10000 });
  check('accounts: a rated game ended after 2 moves explains there is no rematch',
    (await note(A)) && (await note(B)) && (await button(B, 'Rematch').count()) === 0);
}

await done(browser);

// no side effects allowed due to re-export by index.ts
//
// Mini Go boards: static thumbnails (`.mini-board`) and live mini games (`.mini-game`).
// The server renders the initial SVG; live games are updated from websocket "fen" messages,
// which in this Go fork carry the serialized board.

import { h, type VNode } from 'snabbdom';

import { setClockWidget } from '@/game/clock/clockWidget';
import { renderMiniGoban } from '@/go/miniGoban';
import { pubsub } from '@/pubsub';
import { wsSend } from '@/socket';

const COLORS: Color[] = ['white', 'black'];

export const initMiniBoard = (node: HTMLElement): void => {
  const state = node.getAttribute('data-state');
  if (!state) return;
  const [board, , lm] = state.split(',');
  renderMiniGoban(node, board, lm);
};

export const initMiniBoards = (parent?: HTMLElement): void =>
  Array.from((parent || document).getElementsByClassName('mini-board--init')).forEach((el: HTMLElement) => {
    el.classList.remove('mini-board--init');
    initMiniBoard(el);
  });

export const renderClock = (color: Color, time: number): VNode =>
  h(`span.mini-game__clock.mini-game__clock--${color}`, {
    attrs: { 'data-time': time, 'data-managed': 1 },
  });

export const initMiniGame = (node: Element): string | null => {
  const [, color] = (node.getAttribute('data-state') || '').split(',');
  const $el = $(node).removeClass('mini-game--init');
  // we don't know whose turn it is from the board alone: both clocks start paused,
  // and the first live update sets the running one
  COLORS.forEach(c =>
    $el.find('.mini-game__clock--' + c).each(function (this: HTMLElement) {
      setClockWidget(this, { time: parseInt(this.getAttribute('data-time')!), pause: true });
    }),
  );
  return color !== undefined ? node.getAttribute('data-live') : null;
};

export const initMiniGames = (parent?: HTMLElement): void => {
  const nodes = Array.from((parent || document).getElementsByClassName('mini-game--init'));
  const ids = nodes.map(x => initMiniGame(x)).filter(Boolean);
  if (ids.length) pubsub.after('socket.hasConnected').then(() => wsSend('startWatching', ids.join(' ')));
};

export const updateMiniGame = (node: HTMLElement, data: MiniGameUpdateData): void => {
  renderMiniGoban(node, data.board, data.lm);
  const turnColor = data.color;
  const updateClock = (time: number | undefined, color: Color) => {
    const clockEl = node?.querySelector('.mini-game__clock--' + color) as HTMLElement;
    if (clockEl && !isNaN(time!)) setClockWidget(clockEl, { time: time!, pause: color !== turnColor });
  };
  updateClock(data.wc, 'white');
  updateClock(data.bc, 'black');
};

export const finishMiniGame = (node: HTMLElement, win?: 'b' | 'w'): void =>
  COLORS.forEach(color => {
    const clock: HTMLElement | null = node.querySelector('.mini-game__clock--' + color);
    // don't interfere with snabbdom clocks
    if (clock && !clock.dataset['managed'])
      $(clock).replaceWith(
        `<span class="mini-game__result">${win ? (win === color[0] ? 1 : 0) : '½'}</span>`,
      );
  });

interface MiniGameUpdateData {
  board: string;
  color?: Color;
  lm: string;
  wc?: number;
  bc?: number;
}

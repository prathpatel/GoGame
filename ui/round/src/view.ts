import { h, type VNode } from 'snabbdom';

import type RoundCtrl from './ctrl';
import * as go from './goban';
import { status, type GoColor, type PlayerData } from './interfaces';

const svgNs = 'http://www.w3.org/2000/svg';
const s = (sel: string, attrs: Record<string, any>, children: VNode[] = []) =>
  h(sel, { ns: svgNs, attrs } as any, children);

const CELL = 10;
const MARGIN = 9;

export function main(ctrl: RoundCtrl): VNode {
  return h('div.round__app.go-round', [
    h('div.round__app__board.main-board', [board(ctrl)]),
    h('div.round__app__table.go-table', [
      player(ctrl, ctrl.data.opponent),
      moves(ctrl),
      controls(ctrl),
      player(ctrl, ctrl.data.player),
    ]),
  ]);
}

function board(ctrl: RoundCtrl): VNode {
  const size = ctrl.size;
  const full = MARGIN * 2 + CELL * (size - 1);
  const pos = ctrl.displayed;
  const live = ctrl.isLive();
  const scoring = live ? ctrl.data.game.go.scoring : undefined;
  const dead = new Set(scoring?.dead ?? []);
  const lastIndex = pos.lastMove ? go.keyToIndex(pos.lastMove, size) : undefined;
  const coord = (i: number) => [MARGIN + (i % size) * CELL, MARGIN + Math.floor(i / size) * CELL];
  const children: VNode[] = [];

  children.push(stoneGradients());
  children.push(s('rect', { class: 'goban__wood', x: 0, y: 0, width: full, height: full, rx: 1 }));
  const end = MARGIN + (size - 1) * CELL;
  for (let k = 0; k < size; k++) {
    const c = MARGIN + k * CELL;
    children.push(s('line', { class: 'goban__line', x1: MARGIN, y1: c, x2: end, y2: c }));
    children.push(s('line', { class: 'goban__line', x1: c, y1: MARGIN, x2: c, y2: end }));
  }
  for (const p of go.starPoints(size)) {
    const [x, y] = coord(p);
    children.push(s('circle', { class: 'goban__star', cx: x, cy: y, r: 1.1 }));
  }
  if (ctrl.data.pref?.coords !== 0) {
    const cols = 'ABCDEFGHJKLMNOPQRSTUVWXYZ';
    for (let k = 0; k < size; k++) {
      const c = MARGIN + k * CELL;
      children.push(s('text', { class: 'goban__coord', x: c, y: 4.2 }, [cols[k] as any]));
      children.push(s('text', { class: 'goban__coord', x: c, y: full - 1.6 }, [cols[k] as any]));
      children.push(s('text', { class: 'goban__coord', x: 3, y: c + 1.2 }, [String(size - k) as any]));
      children.push(s('text', { class: 'goban__coord', x: full - 3, y: c + 1.2 }, [String(size - k) as any]));
    }
  }
  // territory during scoring
  if (scoring)
    for (const [i, owner] of scoring.score.ownership) {
      if (pos.board[i] !== '.' && !dead.has(i)) continue;
      const [x, y] = coord(i);
      children.push(
        s('rect', {
          class: `goban__territory ${owner === 1 ? 'black' : 'white'}`,
          x: x - 2,
          y: y - 2,
          width: 4,
          height: 4,
        }),
      );
    }
  pos.board.forEach((stone, i) => {
    if (stone === '.') return;
    const [x, y] = coord(i);
    const color = stone === 'b' ? 'black' : 'white';
    children.push(
      s('circle', {
        class: `goban__stone ${color}${dead.has(i) ? ' dead' : ''}`,
        cx: x,
        cy: y,
        r: 4.7,
      }),
    );
    if (i === lastIndex)
      children.push(s('circle', { class: `goban__last ${color}`, cx: x, cy: y, r: 1.9 }));
  });
  if (live && ctrl.data.game.go.ko !== undefined && !scoring) {
    const [x, y] = coord(ctrl.data.game.go.ko);
    children.push(s('rect', { class: 'goban__ko', x: x - 2.5, y: y - 2.5, width: 5, height: 5 }));
  }
  // hover ghost stone
  if (live && ctrl.hover !== undefined && ctrl.isMyTurn() && !scoring && pos.board[ctrl.hover] === '.') {
    const [x, y] = coord(ctrl.hover);
    children.push(s('circle', { class: `goban__ghost ${ctrl.data.player.color}`, cx: x, cy: y, r: 4.7 }));
  }

  const pointAt = (e: MouseEvent): number | undefined => {
    const svg = (e.currentTarget as SVGSVGElement).getBoundingClientRect();
    const scale = full / svg.width;
    const px = (e.clientX - svg.left) * scale,
      py = (e.clientY - svg.top) * scale;
    const x = Math.round((px - MARGIN) / CELL),
      y = Math.round((py - MARGIN) / CELL);
    return x >= 0 && y >= 0 && x < size && y < size ? y * size + x : undefined;
  };

  return h(
    'div.goban-wrap',
    h(
      'svg.goban',
      {
        ns: svgNs,
        attrs: { viewBox: `0 0 ${full} ${full}`, xmlns: svgNs },
        class: {
          playable: ctrl.isMyTurn() && live,
          scoring: !!scoring && !ctrl.isSpectator(),
        },
        on: {
          click: (e: MouseEvent) => {
            const i = pointAt(e);
            if (i !== undefined) ctrl.clickPoint(i);
          },
          mousemove: (e: MouseEvent) => {
            const i = pointAt(e);
            if (i !== ctrl.hover) {
              ctrl.hover = i;
              ctrl.redraw();
            }
          },
          mouseleave: () => {
            ctrl.hover = undefined;
            ctrl.redraw();
          },
        },
      } as any,
      children,
    ),
  );
}

// Gradients used by the stone styles (see ui/lib/css/go/_goban.scss).
const radial = (id: string, stops: [number, string][], cx = '35%', cy = '30%') =>
  s(
    'radialGradient',
    { id, cx, cy, r: '70%' },
    stops.map(([offset, color]) => s('stop', { offset: `${offset}%`, 'stop-color': color })),
  );

const stoneGradients = (): VNode =>
  s('defs', {}, [
    radial('goban-slate', [
      [0, '#5a5a5a'],
      [45, '#1c1c1c'],
      [100, '#000'],
    ]),
    radial('goban-shell', [
      [0, '#ffffff'],
      [60, '#efebe2'],
      [100, '#c9c2b2'],
    ]),
    radial('goban-yunzi-black', [
      [0, '#3a3d38'],
      [70, '#1a1c19'],
      [100, '#0d0e0c'],
    ], '45%', '40%'),
    radial('goban-yunzi-white', [
      [0, '#fbf8ee'],
      [80, '#e9e3cf'],
      [100, '#d4ccb3'],
    ], '45%', '40%'),
    radial('goban-glass-black', [
      [0, '#8a8f99'],
      [20, '#2b2f36'],
      [100, '#050608'],
    ], '30%', '25%'),
    radial('goban-glass-white', [
      [0, '#ffffff'],
      [25, '#f4f7fb'],
      [100, '#b8c2cf'],
    ], '30%', '25%'),
  ]);

const formatClock = (millis: number, tenths: boolean): string => {
  const total = Math.max(0, millis);
  const secs = Math.floor(total / 1000);
  const mins = Math.floor(secs / 60);
  const hours = Math.floor(mins / 60);
  const pad = (n: number) => (n < 10 ? '0' + n : '' + n);
  if (hours > 0) return `${hours}:${pad(mins % 60)}:${pad(secs % 60)}`;
  if (tenths && total < 10000) return `${pad(mins)}:${pad(secs % 60)}.${Math.floor((total % 1000) / 100)}`;
  return `${pad(mins)}:${pad(secs % 60)}`;
};

function player(ctrl: RoundCtrl, p: PlayerData): VNode {
  const game = ctrl.data.game;
  const millis = ctrl.clockMillis(p.color);
  const running = !!ctrl.clock?.running && ctrl.isPlayable() && !ctrl.inScoring() && game.player === p.color;
  const name = p.user
    ? h('a.user-link.ulpt', { attrs: { href: `/@/${p.user.username}` } }, [
        p.user.title ? h('span.utitle', p.user.title + ' ') : null,
        p.user.username,
      ])
    : p.ai
      ? h(
          'span',
          { attrs: { title: aiTitle(p.aiEngine) } },
          p.aiEngine ? `${p.aiEngine} · level ${p.ai}` : `Computer level ${p.ai}`,
        )
      : h('span', p.name || 'Anonymous');
  const emerg = millis !== undefined && ctrl.data.clock && millis < ctrl.data.clock.emerg * 1000;
  return h(`div.go-player.go-player--${p.color}`, { class: { 'to-move': game.player === p.color && ctrl.isPlayable() } }, [
    h('div.go-player__info', [
      h(`span.go-player__stone.${p.color}`),
      h('span.go-player__name', [
        name,
        p.rating && !p.provisional ? h('span.go-player__rating', ` ${p.rating}`) : null,
        p.ratingDiff !== undefined
          ? h(`span.rp.${p.ratingDiff >= 0 ? 'up' : 'down'}`, ` ${p.ratingDiff >= 0 ? '+' : ''}${p.ratingDiff}`)
          : null,
      ]),
      h('span.go-player__online', { class: { on: !!p.onGame || !!p.ai } }),
    ]),
    h('div.go-player__extra', [
      h('span.go-player__prisoners', { attrs: { title: 'Prisoners' } }, `${game.go.prisoners[p.color]} captured`),
      p.color === 'white' ? h('span.go-player__komi', `komi ${game.go.komi}`) : null,
    ]),
    millis !== undefined
      ? h(
          'div.go-clock',
          { class: { running, emerg: !!emerg } },
          formatClock(millis, ctrl.data.pref?.clockTenths !== 0),
        )
      : null,
  ]);
}

function moves(ctrl: RoundCtrl): VNode {
  const size = ctrl.size;
  const start = (ctrl.data.game.startedAtTurn ?? 0) % 2 === 0 ? 'white' : 'black';
  const other = start === 'white' ? 'black' : 'white';
  const items = ctrl.moves.map((m, i) => {
    const color: GoColor = i % 2 === 0 ? start : other;
    return h(
      `move.${color}`,
      {
        class: { active: ctrl.viewing === i + 1 },
        on: { click: () => ctrl.jump(i + 1) },
      },
      [h('index', `${i + 1}`), h('san', go.humanMove(m, size))],
    );
  });
  return h('div.go-moves', [
    h(
      'div.go-moves__list',
      {
        hook: {
          postpatch: (_, vnode) => {
            const el = vnode.elm as HTMLElement;
            const active = el.querySelector('.active') as HTMLElement | null;
            if (active) el.scrollTop = active.offsetTop - el.clientHeight / 2;
            else if (ctrl.isLive()) el.scrollTop = el.scrollHeight;
          },
        },
      },
      items.length ? items : [h('div.go-moves__empty', 'No moves yet')],
    ),
    h('div.go-moves__nav', [
      navButton('⏮', 'First', () => ctrl.jump(0)),
      navButton('◀', 'Previous', () => ctrl.jump(ctrl.viewing - 1)),
      navButton('▶', 'Next', () => ctrl.jump(ctrl.viewing + 1)),
      navButton('⏭', 'Last', () => ctrl.jump(ctrl.positions.length - 1)),
    ]),
  ]);
}

const navButton = (icon: string, title: string, f: () => void) =>
  h('button.fbt', { attrs: { title, type: 'button' }, on: { click: f } }, icon);

const button = (label: string, f: () => void, cls = '') =>
  h(`button.button${cls}`, { attrs: { type: 'button' }, on: { click: f } }, label);

function controls(ctrl: RoundCtrl): VNode {
  const d = ctrl.data;
  const rows: (VNode | null)[] = [];
  if (ctrl.error) rows.push(h('div.go-status.error', ctrl.error));

  if (ctrl.isFinished()) {
    rows.push(h('div.go-status.result', resultText(ctrl)));
    const score = d.game.go.scoring?.score;
    if (score)
      rows.push(
        h('div.go-score', [
          h('div', `Black: ${score.stones.black} stones + ${score.territory.black} territory = ${score.black}`),
          h(
            'div',
            `White: ${score.stones.white} stones + ${score.territory.white} territory + ${score.komi} komi = ${score.white}`,
          ),
        ]),
      );
    if (!ctrl.isSpectator() && d.game.status.id !== status.aborted) {
      if (d.game.boosted)
        // the server refuses: a rated game this short could be used to move ratings around
        rows.push(
          h('div.go-actions', [
            h('span.go-score', 'No rematch: this rated game ended after too few moves to count.'),
          ]),
        );
      else if (ctrl.rematchOfferedBy === d.opponent.color)
        rows.push(
          h('div.go-actions', [
            h('span', 'Your opponent offers a rematch'),
            button('Accept', () => ctrl.rematch(true), '.button-green'),
            button('Decline', () => ctrl.rematch(false), '.button-red'),
          ]),
        );
      else if (ctrl.rematchOfferedBy === d.player.color)
        rows.push(
          h('div.go-actions', [
            h('span', 'Rematch offer sent'),
            button('Cancel', () => ctrl.rematch(false), '.button-empty'),
          ]),
        );
      else rows.push(h('div.go-actions', [button('Rematch', () => ctrl.rematch(true))]));
    }
    rows.push(
      h('div.go-actions', [
        h('a.button.button-empty', { attrs: { href: `/game/export/${d.game.id}.sgf`, download: true } }, 'Download SGF'),
        h('a.button.button-empty', { attrs: { href: '/' } }, 'New game'),
      ]),
    );
    return h('div.go-controls', rows);
  }

  if (ctrl.inScoring()) {
    const sc = d.game.go.scoring!;
    rows.push(
      h('div.go-status', [
        h('strong', 'Counting'),
        h(
          'p',
          ctrl.isSpectator()
            ? 'Both players passed. They are marking dead stones.'
            : 'Both players passed. Click groups to mark them dead or alive, then accept the score.',
        ),
        h('p.go-score-line', `${sc.score.result} (Black ${sc.score.black}, White ${sc.score.white})`),
        sc.accepted.length ? h('p', `Accepted by: ${sc.accepted.join(', ')}`) : null,
      ]),
    );
    if (!ctrl.isSpectator())
      rows.push(
        h('div.go-actions', [
          sc.accepted.includes(d.player.color)
            ? h('span', 'Waiting for your opponent')
            : button('Accept score', ctrl.acceptScore, '.button-green'),
          button('Resume play', ctrl.resumePlay, '.button-empty'),
        ]),
      );
    return h('div.go-controls', rows);
  }

  rows.push(
    h(
      'div.go-status',
      ctrl.isSpectator()
        ? `${d.game.player === 'black' ? 'Black' : 'White'} to play`
        : ctrl.isMyTurn()
          ? 'Your turn'
          : 'Waiting for opponent',
    ),
  );
  if (d.game.go.lastMove === 'pass' && ctrl.isLive())
    rows.push(h('div.go-status.passed', `${d.game.player === 'black' ? 'White' : 'Black'} passed`));

  if (!ctrl.isSpectator()) {
    const opp = d.opponent.color;
    if (ctrl.takebackOffers[opp])
      rows.push(
        h('div.go-actions', [
          h('span', 'Your opponent proposes a takeback'),
          button('Accept', () => ctrl.takeback(true), '.button-green'),
          button('Decline', () => ctrl.takeback(false), '.button-red'),
        ]),
      );
    rows.push(
      h('div.go-actions', [
        button('Pass', ctrl.pass, ctrl.isMyTurn() ? '' : '.disabled'),
        ctrl.canAbort()
          ? button('Abort', ctrl.abort, '.button-empty')
          : button(ctrl.confirmResign ? 'Confirm resign' : 'Resign', ctrl.resign, '.button-red'),
        d.takebackable && ctrl.playedPlies() > 0 && !ctrl.takebackOffers[d.player.color]
          ? button('Takeback', () => ctrl.takeback(true), '.button-empty')
          : null,
      ]),
    );
  }
  return h('div.go-controls', rows);
}

function resultText(ctrl: RoundCtrl): string {
  const g = ctrl.data.game;
  const winner = g.winner === 'black' ? 'Black' : g.winner === 'white' ? 'White' : undefined;
  const loser = g.winner === 'black' ? 'White' : 'Black';
  switch (g.status.id) {
    case status.aborted:
      return 'Game aborted';
    case status.resign:
      return `${loser} resigned • ${winner} wins`;
    case status.outoftime:
      return `${loser} ran out of time • ${winner} wins`;
    case status.timeout:
      return `${loser} left the game • ${winner} wins`;
    case status.noStart:
      return `${loser} didn't move • ${winner} wins`;
    case status.draw:
      return 'Jigo (draw)';
    case status.variantEnd:
      return `${winner} wins by ${g.go.scoring?.score ? g.go.scoring.score.result.replace(/^[BW]\+/, '') + ' points' : 'counting'}`;
    case status.cheat:
      return 'Cheat detected';
    default:
      return winner ? `${winner} wins` : 'Game over';
  }
}

/** "KataGo 20k" => "Played by KataGo, imitating a 20k human player" */
function aiTitle(engine?: string): string {
  if (!engine) return 'Played by the built-in bot';
  const [name, rank] = engine.split(' ');
  return rank ? `Played by ${name}, imitating a ${rank} human player` : `Played by ${name} at full strength`;
}

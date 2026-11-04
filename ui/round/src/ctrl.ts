import * as go from './goban';
import {
  status,
  type EndData,
  type GoColor,
  type MoveEvent,
  type RoundData,
  type RoundOpts,
  type Scoring,
} from './interfaces';

export default class RoundCtrl {
  data: RoundData;
  moves: string[];
  positions: go.Position[];
  /** index of the position displayed; positions.length - 1 is the live position */
  viewing: number;
  hover?: number;
  error?: string;
  takebackOffers: { white?: boolean; black?: boolean } = {};
  rematchOfferedBy?: GoColor;
  confirmResign = false;
  clock?: { white: number; black: number; at: number; running: boolean };
  private flagged = false;
  private clockTimer?: number;

  constructor(
    readonly opts: RoundOpts,
    readonly redraw: () => void,
  ) {
    this.data = opts.data;
    this.moves = this.data.game.go.moves.split(' ').filter(m => m);
    this.positions = go.replay(this.size, this.data.game.go.handicap ?? 0, this.moves);
    this.viewing = this.positions.length - 1;
    if (this.data.opponent.proposingTakeback) this.takebackOffers[this.data.opponent.color] = true;
    if (this.data.player.proposingTakeback) this.takebackOffers[this.data.player.color] = true;
    if (this.data.opponent.offeringRematch) this.rematchOfferedBy = this.data.opponent.color;
    if (this.data.player.offeringRematch) this.rematchOfferedBy = this.data.player.color;
    if (this.data.clock)
      this.clock = {
        white: this.data.clock.white,
        black: this.data.clock.black,
        at: performance.now(),
        running: this.data.clock.running && this.isPlayable(),
      };
    this.clockTimer = window.setInterval(this.tick, 100);
    document.addEventListener('keydown', this.onKey);
  }

  get size(): number {
    return this.data.game.go.size;
  }
  get live(): go.Position {
    return this.positions[this.positions.length - 1];
  }
  get displayed(): go.Position {
    return this.positions[this.viewing];
  }
  isLive = (): boolean => this.viewing === this.positions.length - 1;
  isSpectator = (): boolean => !!this.data.player.spectator;
  isFinished = (): boolean => this.data.game.status.id >= status.aborted;
  isPlayable = (): boolean => this.data.game.status.id < status.aborted;
  playedPlies = (): number => this.data.game.turns - (this.data.game.startedAtTurn ?? 0);
  isMyTurn = (): boolean =>
    !this.isSpectator() && this.isPlayable() && this.data.game.player === this.data.player.color;
  inScoring = (): boolean => this.isPlayable() && !!this.data.game.go.scoring;
  canAbort = (): boolean => this.isPlayable() && !this.isSpectator() && this.playedPlies() < 2;

  private send = (t: string, d?: any, ackable = false): void => this.opts.socketSend?.(t, d, { ackable });

  // ---- player actions

  clickPoint = (i: number): void => {
    if (this.inScoring()) {
      if (!this.isSpectator() && this.live.board[i] !== '.') this.send('scoring-toggle', i);
      return;
    }
    if (!this.isLive()) {
      this.jump(this.positions.length - 1);
      return;
    }
    if (!this.isMyTurn()) return;
    const key = go.indexToKey(i, this.size);
    const next = go.play(this.live, key);
    if (typeof next === 'string') {
      this.error = next;
      this.redraw();
      return;
    }
    this.error = undefined;
    // optimistic update, confirmed by the server's move event
    this.applyLocal(key, next);
    this.send('move', { u: key }, true);
  };

  pass = (): void => {
    if (!this.isMyTurn() || this.inScoring()) return;
    const next = go.play(this.live, 'pass');
    if (typeof next !== 'string') this.applyLocal('pass', next);
    this.send('move', { u: 'pass' }, true);
  };

  private applyLocal(key: string, next: go.Position) {
    this.moves.push(key);
    this.positions.push(next);
    this.viewing = this.positions.length - 1;
    this.data.game.turns++;
    this.data.game.player = next.toMove;
    this.data.game.go.lastMove = key;
    this.data.game.go.prisoners = next.prisoners;
    this.playSound(key === 'pass' ? 'pass' : next.captured.length ? 'capture' : 'move');
    this.redraw();
  }

  resign = (): void => {
    if (this.confirmResign) {
      this.confirmResign = false;
      this.send('resign');
    } else {
      this.confirmResign = true;
      setTimeout(() => {
        this.confirmResign = false;
        this.redraw();
      }, 3000);
    }
    this.redraw();
  };
  abort = (): void => this.send('abort');
  takeback = (yes: boolean): void => this.send(yes ? 'takeback-yes' : 'takeback-no');
  rematch = (yes: boolean): void => this.send(yes ? 'rematch-yes' : 'rematch-no');
  acceptScore = (): void => this.send('scoring-accept');
  resumePlay = (): void => this.send('scoring-resume');

  // ---- replay navigation

  jump = (i: number): void => {
    this.viewing = Math.max(0, Math.min(i, this.positions.length - 1));
    this.redraw();
  };

  private onKey = (e: KeyboardEvent) => {
    if ((e.target as HTMLElement)?.closest?.('input, textarea')) return;
    if (e.key === 'ArrowLeft') this.jump(this.viewing - 1);
    else if (e.key === 'ArrowRight') this.jump(this.viewing + 1);
    else if (e.key === 'ArrowUp' || e.key === 'Home') this.jump(0);
    else if (e.key === 'ArrowDown' || e.key === 'End') this.jump(this.positions.length - 1);
    else return;
    e.preventDefault();
  };

  // ---- clocks

  clockMillis = (color: GoColor): number | undefined => {
    if (!this.clock) return undefined;
    const base = this.clock[color] * 1000;
    if (!this.clock.running || this.data.game.player !== color || !this.isPlayable() || this.inScoring())
      return base;
    return Math.max(0, base - (performance.now() - this.clock.at));
  };

  private tick = () => {
    if (!this.clock?.running || !this.isPlayable() || this.inScoring()) return;
    const color = this.data.game.player;
    const millis = this.clockMillis(color)!;
    if (millis <= 0 && !this.flagged) {
      this.flagged = true;
      this.send('flag', color);
    }
    this.redraw();
  };

  private setClock(c: { white: number; black: number }) {
    this.clock = {
      white: c.white,
      black: c.black,
      at: performance.now(),
      running: this.isPlayable() && this.playedPlies() >= 2,
    };
    this.flagged = false;
  }

  // ---- server events; return true when handled

  receive = (t: string, d: any): boolean => {
    switch (t) {
      case 'move':
        this.onMove(d as MoveEvent);
        return true;
      case 'scoring':
        this.data.game.go.scoring = d as Scoring;
        this.redraw();
        return true;
      case 'endData':
        this.onEnd(d as EndData);
        return false; // let the boot code refresh the game meta
      case 'clock':
        this.setClock(d);
        this.redraw();
        return true;
      case 'takebackOffers':
        this.takebackOffers = d || {};
        this.redraw();
        return true;
      case 'reload':
        if (d?.t === 'rematchOffer') {
          this.rematchOfferedBy = d.d ?? undefined;
          this.redraw();
          return true;
        }
        if (d?.t === 'rematchTaken') {
          location.href = `/${d.d}`;
          return true;
        }
        site.reload();
        return true;
      case 'redirect':
        // site.redirect also stores the anonymous player cookie of the new game (a rematch):
        // without it, the new game opens as a spectator
        site.unload.expected = true;
        site.redirect(d);
        return true;
      case 'resync':
        site.reload();
        return true;
      case 'crowd':
        this.data.player.onGame = d[this.data.player.color];
        this.data.opponent.onGame = d[this.data.opponent.color];
        this.redraw();
        return false;
      default:
        return false;
    }
  };

  private onMove(m: MoveEvent) {
    const confirmsLocal = m.ply === this.data.game.turns && this.moves[this.moves.length - 1] === m.uci;
    if (!confirmsLocal) {
      if (m.ply !== this.data.game.turns + 1) {
        site.reload(); // we missed something
        return;
      }
      const next = go.play(this.live, m.uci);
      if (typeof next === 'string') {
        site.reload();
        return;
      }
      const wasLive = this.isLive();
      this.moves.push(m.uci);
      this.positions.push(next);
      if (wasLive) this.viewing = this.positions.length - 1;
      this.data.game.turns = m.ply;
      this.playSound(m.uci === 'pass' ? 'pass' : (m.captured?.length ?? 0) > 0 ? 'capture' : 'move');
    }
    // the server board is authoritative
    const server = go.parseBoard(m.board);
    if (server.join('') !== this.live.board.join('')) this.live.board = server;
    this.data.game.player = m.ply % 2 === 0 ? 'white' : 'black';
    this.data.game.go.lastMove = m.uci;
    this.data.game.go.prisoners = m.prisoners;
    this.data.game.go.ko = m.ko;
    if (!m.scoring) this.data.game.go.scoring = undefined;
    if (m.clock) this.setClock(m.clock);
    if (m.status) this.data.game.status = m.status;
    if (m.winner) this.data.game.winner = m.winner;
    this.redraw();
  }

  private onEnd(d: EndData) {
    this.data.game.status = d.status;
    this.data.game.winner = d.winner;
    this.data.game.boosted = d.boosted;
    if (d.score && this.data.game.go.scoring) this.data.game.go.scoring.score = d.score;
    if (this.clock) this.clock.running = false;
    if (d.ratingDiff) {
      this.data.player.ratingDiff = d.ratingDiff[this.data.player.color];
      this.data.opponent.ratingDiff = d.ratingDiff[this.data.opponent.color];
    }
    if (!this.isSpectator() && d.status.id !== status.aborted)
      this.playSound(!d.winner ? 'draw' : d.winner === this.data.player.color ? 'victory' : 'defeat');
    window.clearInterval(this.clockTimer);
    this.redraw();
  }

  private playSound(name: string) {
    const sound = name === 'pass' ? 'genericNotify' : name;
    site.sound.play(sound).catch(() => {});
  }
}

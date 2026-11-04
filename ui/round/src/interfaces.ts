import type { ChatOpts } from 'lib/chat/interfaces';

export type GoColor = 'black' | 'white';

export interface Score {
  black: number;
  white: number;
  komi: number;
  result: string;
  territory: { black: number; white: number };
  stones: { black: number; white: number };
  ownership: [number, 1 | 2][];
}

export interface Scoring {
  dead: number[];
  accepted: GoColor[];
  score: Score;
}

export interface GoData {
  size: number;
  komi: number;
  board: string;
  moves: string;
  prisoners: { black: number; white: number };
  handicap?: number;
  lastMove?: string;
  ko?: number;
  scoring?: Scoring;
}

export interface StatusData {
  id: number;
  name: string;
}

export interface UserData {
  id: string;
  username: string;
  title?: string;
  online?: boolean;
  perfs?: Record<string, { rating: number; prov?: boolean }>;
}

export interface PlayerData {
  color: GoColor;
  id?: string;
  version?: number;
  user?: UserData;
  name?: string;
  rating?: number;
  ratingDiff?: number;
  provisional?: boolean;
  ai?: number;
  /** external engine playing this computer level, e.g. "KataGo" */
  aiEngine?: string;
  onGame?: boolean;
  isGone?: number | boolean;
  spectator?: boolean;
  prisoners?: number;
  proposingTakeback?: boolean;
  offeringRematch?: boolean;
}

export interface ClockData {
  running: boolean;
  initial: number;
  increment: number;
  white: number;
  black: number;
  emerg: number;
  moretime?: number;
}

export interface GameData {
  id: string;
  speed: string;
  perf: string;
  rated: boolean;
  source?: string;
  startedAtTurn?: number;
  go: GoData;
  turns: number;
  status: StatusData;
  winner?: GoColor;
  player: GoColor;
  rematch?: string;
  /** a rated game that ended in very few moves: no rematch */
  boosted?: boolean;
}

export interface RoundData {
  game: GameData;
  player: PlayerData;
  opponent: PlayerData;
  orientation?: GoColor;
  clock?: ClockData;
  correspondence?: { daysPerTurn: number; white: number; black: number };
  takebackable?: boolean;
  moretimeable?: boolean;
  pref?: { coords?: number; replay?: number; clockTenths?: number };
  expiration?: { idleMillis: number; millisToMove: number };
}

export interface RoundOpts {
  data: RoundData;
  userId?: string;
  chat?: Partial<ChatOpts> & { instance?: any };
  socketSend?: (t: string, d?: any, o?: any) => void;
}

export interface MoveEvent {
  uci: string;
  san: string;
  board: string;
  ply: number;
  prisoners: { black: number; white: number };
  captured?: number[];
  ko?: number;
  scoring?: boolean;
  clock?: { white: number; black: number; lag?: number };
  status?: StatusData;
  winner?: GoColor;
}

export interface EndData {
  winner?: GoColor;
  boosted?: boolean;
  status: StatusData;
  clock?: { wc: number; bc: number };
  ratingDiff?: { white: number; black: number };
  score?: Score;
}

export const status = {
  created: 10,
  started: 20,
  aborted: 25,
  mate: 30,
  resign: 31,
  timeout: 33,
  draw: 34,
  outoftime: 35,
  cheat: 36,
  noStart: 37,
  unknownFinish: 38,
  variantEnd: 60,
};

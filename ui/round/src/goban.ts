// Go rules on the client: captures, suicide, simple and positional ko.
// The server is authoritative; this is used to replay games and to avoid
// sending obviously illegal moves.

export type Stone = '.' | 'b' | 'w';
export type GoColor = 'black' | 'white';

export const stoneOf = (c: GoColor): Stone => (c === 'black' ? 'b' : 'w');
export const opposite = (c: GoColor): GoColor => (c === 'black' ? 'white' : 'black');

export interface Position {
  size: number;
  board: Stone[];
  toMove: GoColor;
  prisoners: { black: number; white: number };
  lastMove?: string; // sgf key or 'pass'
  captured: number[];
  passes: number;
  seen: Set<string>;
}

const letters = 'abcdefghijklmnopqrs';
const humanCols = 'ABCDEFGHJKLMNOPQRSTUVWXYZ';

export const keyToIndex = (key: string, size: number): number | undefined => {
  if (key.length !== 2) return undefined;
  const x = letters.indexOf(key[0]),
    y = letters.indexOf(key[1]);
  return x >= 0 && y >= 0 && x < size && y < size ? y * size + x : undefined;
};

export const indexToKey = (i: number, size: number): string => letters[i % size] + letters[Math.floor(i / size)];

export const humanMove = (key: string, size: number): string => {
  const i = keyToIndex(key, size);
  if (i === undefined) return 'pass';
  return `${humanCols[i % size]}${size - Math.floor(i / size)}`;
};

export const neighbours = (i: number, size: number): number[] => {
  const x = i % size,
    res: number[] = [];
  if (x > 0) res.push(i - 1);
  if (x < size - 1) res.push(i + 1);
  if (i >= size) res.push(i - size);
  if (i < size * size - size) res.push(i + size);
  return res;
};

export function group(board: Stone[], i: number, size: number): { stones: number[]; liberties: number } {
  const color = board[i];
  const stones = [i];
  const seen = new Set([i]);
  const libs = new Set<number>();
  for (let k = 0; k < stones.length; k++)
    for (const n of neighbours(stones[k], size)) {
      if (board[n] === '.') libs.add(n);
      else if (board[n] === color && !seen.has(n)) {
        seen.add(n);
        stones.push(n);
      }
    }
  return { stones, liberties: libs.size };
}

export function starPoints(size: number): number[] {
  const edge = size < 13 ? 2 : 3,
    far = size - 1 - edge,
    mid = Math.floor(size / 2);
  const p = (x: number, y: number) => y * size + x;
  const corners = [p(edge, edge), p(far, edge), p(edge, far), p(far, far)];
  if (size === 9) return [p(mid, mid), ...corners];
  return [...corners, p(mid, edge), p(edge, mid), p(far, mid), p(mid, far), p(mid, mid)];
}

/** Same placement as the server's goban.Handicap. */
export function handicapPoints(size: number, handicap: number): number[] {
  const edge = size < 13 ? 2 : 3,
    far = size - 1 - edge,
    mid = Math.floor(size / 2);
  const p = (x: number, y: number) => y * size + x;
  const [tr, bl, br, tl] = [p(far, edge), p(edge, far), p(far, far), p(edge, edge)];
  const [center, left, right, top, bottom] = [p(mid, mid), p(edge, mid), p(far, mid), p(mid, edge), p(mid, far)];
  const h = Math.min(handicap, size === 9 ? 5 : 9);
  if (h < 2) return [];
  const lists: Record<number, number[]> = {
    2: [tr, bl],
    3: [tr, bl, br],
    4: [tr, bl, br, tl],
    5: [tr, bl, br, tl, center],
    6: [tr, bl, br, tl, left, right],
    7: [tr, bl, br, tl, left, right, center],
    8: [tr, bl, br, tl, left, right, top, bottom],
  };
  return lists[h] ?? [tr, bl, br, tl, left, right, top, bottom, center];
}

export function initialPosition(size: number, handicap: number): Position {
  const board: Stone[] = Array(size * size).fill('.');
  const hc = handicapPoints(size, handicap);
  hc.forEach(i => (board[i] = 'b'));
  return {
    size,
    board,
    toMove: hc.length ? 'white' : 'black',
    prisoners: { black: 0, white: 0 },
    captured: [],
    passes: 0,
    seen: new Set(),
  };
}

/** Returns the next position, or an error message if the move is illegal. */
export function play(pos: Position, key: string): Position | string {
  const { size } = pos;
  if (key === 'pass')
    return {
      ...pos,
      toMove: opposite(pos.toMove),
      lastMove: 'pass',
      captured: [],
      passes: pos.passes + 1,
    };
  const i = keyToIndex(key, size);
  if (i === undefined) return 'Off the board';
  if (pos.board[i] !== '.') return 'Occupied';
  const me = stoneOf(pos.toMove),
    enemy = stoneOf(opposite(pos.toMove));
  const board = pos.board.slice();
  board[i] = me;
  const captured: number[] = [];
  for (const n of neighbours(i, size))
    if (board[n] === enemy) {
      const g = group(board, n, size);
      if (g.liberties === 0) g.stones.forEach(s => ((board[s] = '.'), captured.push(s)));
    }
  if (!captured.length && group(board, i, size).liberties === 0) return 'Suicide';
  const hash = board.join('');
  const prev = pos.board.join('');
  if (hash === prev || pos.seen.has(hash)) return 'Ko: this would repeat a previous position';
  const prisoners = { ...pos.prisoners };
  prisoners[pos.toMove] += captured.length;
  const seen = new Set(pos.seen);
  seen.add(prev);
  return { size, board, toMove: opposite(pos.toMove), prisoners, lastMove: key, captured, passes: 0, seen };
}

/** All positions of a game, starting with the initial one. */
export function replay(size: number, handicap: number, moves: string[]): Position[] {
  const positions = [initialPosition(size, handicap)];
  for (const m of moves) {
    const next = play(positions[positions.length - 1], m);
    if (typeof next === 'string') break;
    positions.push(next);
  }
  return positions;
}

export const parseBoard = (str: string): Stone[] => str.split('') as Stone[];

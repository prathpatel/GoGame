// Small static Go boards (game lists, lobby, mini games), drawn as SVG.

const letters = 'abcdefghijklmnopqrs';
const CELL = 10;
const MARGIN = 6;

const keyToIndex = (key: string | undefined, size: number): number | undefined => {
  if (!key || key.length !== 2) return undefined;
  const x = letters.indexOf(key[0]),
    y = letters.indexOf(key[1]);
  return x >= 0 && y >= 0 && x < size && y < size ? y * size + x : undefined;
};

const starPoints = (size: number): number[] => {
  const edge = size < 13 ? 2 : 3,
    far = size - 1 - edge,
    mid = Math.floor(size / 2);
  const p = (x: number, y: number) => y * size + x;
  const corners = [p(edge, edge), p(far, edge), p(edge, far), p(far, far)];
  if (size === 9) return [p(mid, mid), ...corners];
  return [...corners, p(mid, edge), p(edge, mid), p(far, mid), p(mid, far), p(mid, mid)];
};

/** `board` has one char per point: '.', 'b' or 'w'. */
export function miniGobanSvg(board: string, size?: number, lastMove?: string): string {
  const n = size || Math.round(Math.sqrt(board.length)) || 19;
  const full = MARGIN * 2 + CELL * (n - 1);
  const end = MARGIN + (n - 1) * CELL;
  const parts: string[] = [
    `<svg class="goban-mini" viewBox="0 0 ${full} ${full}" xmlns="http://www.w3.org/2000/svg">`,
    `<rect class="goban-mini__bg" width="${full}" height="${full}"/>`,
    '<g class="goban-mini__lines">',
  ];
  for (let k = 0; k < n; k++) {
    const c = MARGIN + k * CELL;
    parts.push(`<line x1="${MARGIN}" y1="${c}" x2="${end}" y2="${c}"/><line x1="${c}" y1="${MARGIN}" x2="${c}" y2="${end}"/>`);
  }
  parts.push('</g>');
  for (const p of starPoints(n))
    parts.push(
      `<circle class="goban-mini__star" cx="${MARGIN + (p % n) * CELL}" cy="${MARGIN + Math.floor(p / n) * CELL}" r="1.2"/>`,
    );
  const last = keyToIndex(lastMove, n);
  for (let i = 0; i < board.length; i++) {
    const ch = board[i];
    if (ch !== 'b' && ch !== 'w') continue;
    const cx = MARGIN + (i % n) * CELL,
      cy = MARGIN + Math.floor(i / n) * CELL;
    const color = ch === 'b' ? 'black' : 'white';
    parts.push(`<circle class="goban-mini__stone ${color}" cx="${cx}" cy="${cy}" r="4.6"/>`);
    if (i === last) parts.push(`<circle class="goban-mini__last ${color}" cx="${cx}" cy="${cy}" r="2"/>`);
  }
  parts.push('</svg>');
  return parts.join('');
}

/** Fill `.goban-wrap` elements of mini games from their `data-state="board,color,lastMove"`. */
export function renderMiniGoban(el: HTMLElement, board: string, lastMove?: string): void {
  const wrap = el.classList.contains('goban-wrap') ? el : el.querySelector<HTMLElement>('.goban-wrap');
  if (wrap) wrap.innerHTML = miniGobanSvg(board, undefined, lastMove);
}

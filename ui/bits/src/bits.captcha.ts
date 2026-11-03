import { miniGobanSvg } from 'lib/go/miniGoban';
import * as xhr from 'lib/xhr';

// Signup captcha: a small Go problem. Click the point where Black captures.
const letters = 'abcdefghijklmnopqrs';

function init() {
  $('div.captcha').each(function (this: HTMLElement) {
    if (this.dataset.initialized) return;
    this.dataset.initialized = '1';

    const $captcha = $(this);
    const boardEl = this.querySelector<HTMLElement>('.goban-captcha');
    if (!boardEl) return;
    const $input = $captcha.find('input[name$="move"]').val('');
    const board = boardEl.dataset.board!;
    const size = parseInt(boardEl.dataset.size!);
    let solved = false;

    const render = (played?: number) => {
      const b = played === undefined ? board : board.slice(0, played) + 'b' + board.slice(played + 1);
      const last = played === undefined ? undefined : letters[played % size] + letters[Math.floor(played / size)];
      boardEl.innerHTML = miniGobanSvg(b, size, last);
    };
    render();

    boardEl.addEventListener('click', (e: MouseEvent) => {
      if (solved) return;
      const svg = boardEl.querySelector('svg')!.getBoundingClientRect();
      // must match miniGoban's geometry: cell 10, margin 6
      const full = 12 + 10 * (size - 1);
      const scale = full / svg.width;
      const x = Math.round(((e.clientX - svg.left) * scale - 6) / 10);
      const y = Math.round(((e.clientY - svg.top) * scale - 6) / 10);
      if (x < 0 || y < 0 || x >= size || y >= size) return;
      const i = y * size + x;
      if (board[i] !== '.') return;
      render(i);
      const solution = letters[x] + letters[y];
      $input.val(solution);
      $captcha.removeClass('success failure');
      xhr.text(xhr.url($captcha.data('check-url'), { solution })).then(data => {
        solved = data === '1';
        $captcha.toggleClass('success', solved).toggleClass('failure', !solved);
        if (!solved) setTimeout(() => render(), 600);
      });
    });
  });
}

init();

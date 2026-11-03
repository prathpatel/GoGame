import { hl, bind, type VNode } from 'lib/view';
import { text as xhrText, form as xhrForm } from 'lib/xhr';

import { PaneCtrl } from './interfaces';
import { header } from './util';

// Go board styles. Names and colors match modules/pref Theme and ui/lib/css/go/_goban.scss.
export const boardStyles: Record<string, { label: string; wood: string; line: string }> = {
  kaya: { label: 'Kaya', wood: '#dcb35c', line: '#3b2a14' },
  'shin-kaya': { label: 'Shin-kaya', wood: '#ecd29a', line: '#4a3820' },
  katsura: { label: 'Katsura', wood: '#e3b27a', line: '#45301b' },
  walnut: { label: 'Walnut', wood: '#a8743e', line: '#24170a' },
  bamboo: { label: 'Bamboo', wood: '#d4ba74', line: '#3e3015' },
  paper: { label: 'Paper', wood: '#f2ead8', line: '#5a5246' },
  slate: { label: 'Slate', wood: '#9fb0bb', line: '#23313b' },
  green: { label: 'Green felt', wood: '#9fb57e', line: '#233016' },
  night: { label: 'Night', wood: '#343a42', line: '#a7b0ba' },
};

/** A tiny 5×5 board corner with a black and a white stone. */
const preview = (wood: string, line: string): VNode => {
  const lines = [2, 6, 10, 14, 18].flatMap(c => [
    hl('line', { attrs: { x1: 2, y1: c, x2: 18, y2: c, stroke: line, 'stroke-width': 0.4 } }),
    hl('line', { attrs: { x1: c, y1: 2, x2: c, y2: 18, stroke: line, 'stroke-width': 0.4 } }),
  ]);
  return hl('svg', { attrs: { viewBox: '0 0 20 20', width: '100%', height: '100%' } }, [
    hl('rect', { attrs: { width: 20, height: 20, fill: wood } }),
    ...lines,
    hl('circle', { attrs: { cx: 6, cy: 10, r: 1.8, fill: '#111' } }),
    hl('circle', { attrs: { cx: 10, cy: 6, r: 1.8, fill: '#f6f4ee', stroke: '#8c8578', 'stroke-width': 0.3 } }),
  ]);
};

export class BoardCtrl extends PaneCtrl {
  render = (): VNode =>
    hl('div.sub.board.go-boards', [
      header(i18n.site.board, this.close),
      hl(
        'div.list',
        this.list.map(t => {
          const style = boardStyles[t] ?? boardStyles.kaya;
          return hl(
            'button.go-board-style',
            {
              key: t,
              hook: bind('click', () => this.setBoard(t)),
              attrs: { title: style.label, type: 'button' },
              class: { active: this.current === t },
            },
            [hl('span.preview', preview(style.wood, style.line)), hl('span.name', style.label)],
          );
        }),
      ),
    ]);

  private get data() {
    return this.root.data.board.d2;
  }

  private get list(): string[] {
    return this.data.list.map(t => t.name);
  }

  private get current() {
    return this.data.current;
  }

  private readonly setBoard = (t: string) => {
    this.data.current = t;
    document.body.dataset.board = t;
    xhrText('/pref/theme', { body: xhrForm({ theme: t }), method: 'post' }).catch(() =>
      site.announce({ msg: 'Failed to save board preference' }),
    );
    this.redraw();
  };
}

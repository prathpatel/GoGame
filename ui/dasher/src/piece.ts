import { h, type VNode } from 'snabbdom';

import { bind } from 'lib/view';
import { text as xhrText, form as xhrForm } from 'lib/xhr';

import { PaneCtrl } from './interfaces';
import { header } from './util';

// Go stone styles. Names match modules/pref PieceSet and ui/lib/css/go/_goban.scss.
const stoneStyles: Record<string, { label: string; black: string; white: string; whiteStroke: string }> = {
  'shell-slate': {
    label: 'Shell & slate',
    black: 'radial-gradient(circle at 35% 30%, #5a5a5a, #1c1c1c 45%, #000)',
    white: 'radial-gradient(circle at 35% 30%, #fff, #efebe2 60%, #c9c2b2)',
    whiteStroke: '#8c8578',
  },
  yunzi: {
    label: 'Yunzi',
    black: 'radial-gradient(circle at 45% 40%, #3a3d38, #1a1c19 70%, #0d0e0c)',
    white: 'radial-gradient(circle at 45% 40%, #fbf8ee, #e9e3cf 80%, #d4ccb3)',
    whiteStroke: '#b9b2a2',
  },
  flat: { label: 'Flat', black: '#111', white: '#f6f4ee', whiteStroke: '#8c8578' },
  glass: {
    label: 'Glass',
    black: 'radial-gradient(circle at 30% 25%, #8a8f99, #2b2f36 20%, #050608)',
    white: 'radial-gradient(circle at 30% 25%, #fff, #f4f7fb 25%, #b8c2cf)',
    whiteStroke: '#8c8578',
  },
  outline: { label: 'Outline', black: '#111', white: 'transparent', whiteStroke: '#111' },
};

export class PieceCtrl extends PaneCtrl {
  render(): VNode {
    return h('div.sub.piece.go-stones', [
      header('Stones', () => this.close()),
      h(
        'div.list',
        this.data.list.map(({ name }) => {
          const st = stoneStyles[name] ?? stoneStyles.flat;
          return h(
            'button.go-stone-style',
            {
              key: name,
              attrs: { title: st.label, type: 'button' },
              hook: bind('click', () => this.set(name)),
              class: { active: this.data.current === name },
            },
            [
              h('span.stones', [
                h('span.stone', { attrs: { style: `background:${st.black}` } }),
                h('span.stone', { attrs: { style: `background:${st.white};border-color:${st.whiteStroke}` } }),
              ]),
              h('span.name', st.label),
            ],
          );
        }),
      ),
    ]);
  }

  apply = (t: string = this.data.current): void => {
    this.data.current = t;
    document.body.dataset.pieceSet = t;
  };

  private get data() {
    return this.root.data.piece.d2;
  }

  private readonly set = (t: string) => {
    this.apply(t);
    xhrText('/pref/pieceSet', { body: xhrForm({ pieceSet: t }), method: 'post' }).catch(() =>
      site.announce({ msg: 'Failed to save stone preference' }),
    );
    this.redraw();
  };
}

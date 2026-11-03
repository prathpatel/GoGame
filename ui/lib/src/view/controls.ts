// no side effects allowed due to re-export by index.ts

import { h, type Hooks, type VNode, type Attrs, type On } from 'snabbdom';

import { toggle as baseToggle, type Toggle } from '@/index';
import { licon } from '@/licon';
import { onInsert } from '@/view/snabbdom';
import * as xhr from '@/xhr';

export function enter<E extends HTMLElement>(effect: (target: E) => void) {
  return (e: Event): void => {
    if (e instanceof KeyboardEvent && e.key === 'Enter') effect(e.target as E);
  };
}

export function toggleBoxInit(): void {
  $('.toggle-box--toggle:not(.toggle-box--ready)').each(function (this: HTMLFieldSetElement) {
    const toggle = () => this.classList.toggle('toggle-box--toggle-off');
    $(this).addClass('toggle-box--ready').children('legend').on('click', toggle).on('keydown', enter(toggle));
  });
}

export function rangeConfig(read: () => number, write: (value: number) => void): Hooks {
  return {
    ...onInsert<HTMLInputElement>(el => {
      el.value = String(read());
      el.addEventListener('input', () => write(parseInt(el.value)));
      el.addEventListener('mouseout', () => el.blur());
    }),
    update: (_, v: VNode) => {
      (v.elm as HTMLInputElement).value = `${read()}`; // force redraw on external value change
    },
  };
}

export const boolPrefXhrToggle = (prefKey: string, val: boolean, effect: () => void = site.reload): Toggle =>
  baseToggle(val, async v => {
    await xhr.text(`/pref/${prefKey}`, { method: 'post', body: xhr.form({ [prefKey]: v ? '1' : '0' }) });
    effect();
  });

export function copyMeInput(content: string, opts: { inputAttrs?: Attrs; on?: On } = {}): VNode {
  return h('div.copy-me', [
    h('input.copy-me__target', {
      attrs: { spellcheck: 'false', ...opts.inputAttrs },
      props: { value: content },
      on: opts.on,
    }),
    h('button.copy-me__button.button.button-metal', {
      attrs: { 'data-icon': licon.Clipboard, title: i18n.site.copyToClipboard },
    }),
  ]);
}

export const addPasswordVisibilityToggleListener = (): void => {
  $('.password-wrapper').each(function (this: HTMLElement) {
    const $wrapper = $(this);
    const $button = $wrapper.find('.password-reveal');
    $button.on('click', (e: PointerEvent) => {
      e.preventDefault();
      const $input = $wrapper.find('input');
      const type = $input.attr('type') === 'password' ? 'text' : 'password';
      $input.attr('type', type);
      $button.toggleClass('revealed', type === 'text');
      $button.attr('aria-pressed', String(type === 'text'));
    });
  });
};

// Loading spinner: a black and a white stone circling each other (styles in lib/css/component/_loader.scss)
export const spinnerHtml: string = $html`
  <div class="spinner" aria-label="loading">
    <svg viewBox="-2 -2 54 54"><circle class="spinner__track" cx="25" cy="25" r="16"/><g class="spinner__orbit"><circle class="spinner__black" cx="25" cy="9" r="7.5"/><circle class="spinner__white" cx="25" cy="41" r="7.5"/></g></svg>
  </div>`;

export const spinnerVdom = (box = '-2 -2 54 54'): VNode =>
  h('div.spinner', { 'aria-label': 'loading' }, [
    h('svg', { attrs: { viewBox: box } }, [
      h('circle.spinner__track', { attrs: { cx: 25, cy: 25, r: 16 } }),
      h('g.spinner__orbit', [
        h('circle.spinner__black', { attrs: { cx: 25, cy: 9, r: 7.5 } }),
        h('circle.spinner__white', { attrs: { cx: 25, cy: 41, r: 7.5 } }),
      ]),
    ]),
  ]);

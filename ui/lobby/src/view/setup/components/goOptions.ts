import { hl } from 'lib/view';

import type SetupController from '@/setupCtrl';
import { boardSizes, handicaps } from '@/setupCtrl';

/** Board size: 9×9, 13×13 or 19×19. */
export const sizePicker = (setupCtrl: SetupController) =>
  hl('div.go-size', [
    hl('span.label', 'Board'),
    hl(
      'div.go-size__buttons',
      boardSizes.map(size =>
        hl(
          'button.button.go-size__button',
          {
            attrs: { type: 'button', 'aria-pressed': `${setupCtrl.size() === size}` },
            class: { active: setupCtrl.size() === size, 'button-empty': setupCtrl.size() !== size },
            on: { click: () => setupCtrl.size(size) },
          },
          `${size}×${size}`,
        ),
      ),
    ),
  ]);

/** Handicap stones for Black, with half a point of komi. */
export const handicapPicker = (setupCtrl: SetupController) => {
  const max = setupCtrl.size() === 9 ? 5 : 9;
  return hl('div.go-handicap.label-select', [
    hl('label', { attrs: { for: 'sf_handicap' } }, 'Handicap'),
    hl(
      'select#sf_handicap',
      {
        on: { change: (e: Event) => setupCtrl.handicap(parseInt((e.target as HTMLSelectElement).value)) },
      },
      handicaps
        .filter(h => h <= max)
        .map(h =>
          hl(
            'option',
            { attrs: { value: h, selected: setupCtrl.handicap() === h } },
            h === 0 ? 'None (komi 7.5)' : `${h} stones (komi 0.5)`,
          ),
        ),
    ),
  ]);
};

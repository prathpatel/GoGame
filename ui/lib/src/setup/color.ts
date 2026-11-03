import type { Prop } from '@/index';

export type ColorChoice = Color | 'random';

export type ColorProp = Prop<ColorChoice>;

// Black first: Black moves first in Go
export const colors: ColorChoice[] = ['black', 'random', 'white'];

export const colorChoiceName = (color: ColorChoice): string =>
  color === 'random' ? i18n.site.randomColor : i18n.site[color];

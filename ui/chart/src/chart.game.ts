import { gridColor, tooltipBgColor, fontFamily, maybeChart, colorSeries } from './index';

export { gridColor, colorSeries, tooltipBgColor, fontFamily, maybeChart };

// lichess' analysis charts (accuracy, move times) need engine analysis, which Go games don't have yet
export function initModule(): Record<string, never> {
  return {};
}

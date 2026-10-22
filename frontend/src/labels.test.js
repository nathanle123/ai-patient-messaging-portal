import { describe, expect, it } from 'vitest';
import { timeAgo } from './labels.js';

describe('timeAgo', () => {
  const now = Date.parse('2026-10-21T12:00:00Z');
  it('formats seconds, minutes, hours and days', () => {
    expect(timeAgo('2026-10-21T11:59:30Z', now)).toBe('30s ago');
    expect(timeAgo('2026-10-21T11:15:00Z', now)).toBe('45m ago');
    expect(timeAgo('2026-10-21T02:00:00Z', now)).toBe('10h ago');
    expect(timeAgo('2026-10-17T12:00:00Z', now)).toBe('4d ago');
  });
});

describe('niceScale', () => {
  it('uses 1/2/5 steps and covers the data', async () => {
    const { niceScale } = await import('./components/charts.jsx');
    expect(niceScale(37)).toEqual({ max: 40, step: 10 });
    expect(niceScale(18)).toEqual({ max: 20, step: 5 });
    expect(niceScale(3)).toEqual({ max: 3, step: 1 });
    expect(niceScale(0)).toEqual({ max: 1, step: 1 });
  });
});

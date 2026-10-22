// Small dependency-free SVG charts for the admin dashboard. Mark specs follow the project's data-viz
// rules: bars <= 24px with 4px rounded data-ends, 2px surface gaps between stacked segments, 2px lines,
// hairline recessive grid, legend for >= 2 series, hover tooltips, and a table view for every chart.
import { useMemo, useRef, useState } from 'react';

export const CATEGORY_COLORS = {
  symptoms: 'var(--series-1)',
  medication_refill: 'var(--series-2)',
  scheduling: 'var(--series-3)',
  test_results: 'var(--series-4)',
  billing: 'var(--series-5)',
  other: 'var(--series-6)',
  unclassified: 'var(--series-none)',
};
// Urgency is a state, so it uses the reserved status colors, always paired with a text label.
export const URGENCY_COLORS = {
  urgent: 'var(--status-critical)',
  soon: 'var(--status-warning)',
  routine: 'var(--status-good)',
  unclassified: 'var(--series-none)',
};

export function compact(n) {
  if (n == null) return '–';
  if (Math.abs(n) >= 1000) return `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}K`;
  return String(Math.round(n * 10) / 10);
}

/** Axis scale with clean tick steps (1, 2 or 5 x 10^n): returns { max, step }. */
export function niceScale(dataMax, targetTicks = 4) {
  if (dataMax <= 0) return { max: 1, step: 1 };
  const raw = dataMax / targetTicks;
  const p = 10 ** Math.floor(Math.log10(raw));
  const n = raw / p;
  const step = Math.max(1, (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p);
  return { max: Math.ceil(dataMax / step) * step, step };
}

export function Legend({ keys, colors, labels }) {
  return (
    <ul className="legend">
      {keys.map((k) => (
        <li key={k}><span className="swatch" style={{ background: colors[k] }} />{labels[k] ?? k}</li>
      ))}
    </ul>
  );
}

export function ChartFrame({ title, subtitle, table, children, legend }) {
  const [asTable, setAsTable] = useState(false);
  return (
    <section className="card chart-card">
      <div className="card-head">
        <div>
          <h3>{title}</h3>
          {subtitle && <p className="muted small">{subtitle}</p>}
        </div>
        <span className="spacer" />
        <button className="btn btn-quiet small" onClick={() => setAsTable(!asTable)} aria-pressed={asTable}>
          {asTable ? 'Chart' : 'Table'}
        </button>
      </div>
      {legend && !asTable && legend}
      {asTable ? table : children}
    </section>
  );
}

export function DataTable({ columns, rows }) {
  return (
    <div className="table-wrap">
      <table className="data-table">
        <thead><tr>{columns.map((c) => <th key={c.key} className={c.num ? 'num' : ''}>{c.label}</th>)}</tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>{columns.map((c) => <td key={c.key} className={c.num ? 'num' : ''}>{r[c.key]}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Horizontal bars, one per key, value at the tip. */
export function BarList({ data, keys, colors, labels }) {
  const max = Math.max(1, ...keys.map((k) => data[k] ?? 0));
  const total = keys.reduce((s, k) => s + (data[k] ?? 0), 0);
  return (
    <ul className="barlist">
      {keys.map((k) => {
        const v = data[k] ?? 0;
        const pct = total ? Math.round((v / total) * 100) : 0;
        return (
          <li key={k} title={`${labels[k] ?? k}: ${v} (${pct}%)`}>
            <span className="barlist-label">{labels[k] ?? k}</span>
            <span className="barlist-track">
              <span className="barlist-bar" style={{ width: `${(v / max) * 100}%`, background: colors[k] }} />
            </span>
            <span className="barlist-value">{v}<span className="muted"> · {pct}%</span></span>
          </li>
        );
      })}
    </ul>
  );
}

const H = 180;
const PAD = { top: 8, right: 8, bottom: 22, left: 34 };

function useWidth() {
  const ref = useRef(null);
  const [w, setW] = useState(320);
  const observer = useMemo(() => (typeof ResizeObserver === 'undefined' ? null
    : new ResizeObserver(([e]) => setW(Math.max(280, e.contentRect.width)))), []);
  const setRef = (el) => {
    if (ref.current && observer) observer.unobserve(ref.current);
    ref.current = el;
    if (el && observer) observer.observe(el);
  };
  return [setRef, w];
}

function Axis({ width, max, step }) {
  const lines = [];
  for (let i = 0; i * step <= max; i += 1) {
    const v = step * i;
    const y = PAD.top + (H - PAD.top - PAD.bottom) * (1 - v / max);
    lines.push(
      <g key={i}>
        <line x1={PAD.left} x2={width - PAD.right} y1={y} y2={y} className={i === 0 ? 'axis-base' : 'grid'} />
        <text x={PAD.left - 6} y={y + 3} className="tick" textAnchor="end">{compact(v)}</text>
      </g>,
    );
  }
  return <>{lines}</>;
}

/** Stacked columns per bucket. series: ordered keys; each bucket has values[key]. */
export function StackedColumns({ buckets, series, colors, labels, formatBucket, everyNthLabel = 1 }) {
  const [setRef, width] = useWidth();
  const [hover, setHover] = useState(null);
  const inner = width - PAD.left - PAD.right;
  const band = inner / Math.max(1, buckets.length);
  const barW = Math.min(24, band * 0.7);
  const { max, step } = niceScale(Math.max(0, ...buckets.map((b) => series.reduce((s, k) => s + (b.values[k] ?? 0), 0))));
  const plotH = H - PAD.top - PAD.bottom;
  const y = (v) => (v / max) * plotH;

  return (
    <div className="chart" ref={setRef} onMouseLeave={() => setHover(null)}>
      <svg width={width} height={H} role="img" aria-label="stacked column chart">
        <Axis width={width} max={max} step={step} />
        {buckets.map((b, i) => {
          const x = PAD.left + band * i + (band - barW) / 2;
          let base = H - PAD.bottom;
          const segs = series.filter((k) => (b.values[k] ?? 0) > 0);
          return (
            <g key={b.key} onMouseEnter={() => setHover(i)}>
              <rect x={PAD.left + band * i} y={PAD.top} width={band} height={plotH} fill="transparent" />
              {segs.map((k, j) => {
                const h = y(b.values[k]);
                const top = base - h;
                const last = j === segs.length - 1;
                const gap = j > 0 ? 2 : 0; // 2px surface gap between stacked segments
                const el = last
                  ? <path key={k} d={roundedTop(x, top, barW, h - gap, 4)} fill={colors[k]} opacity={hover == null || hover === i ? 1 : 0.55} />
                  : <rect key={k} x={x} y={top} width={barW} height={Math.max(0, h - gap)} fill={colors[k]} opacity={hover == null || hover === i ? 1 : 0.55} />;
                base = top;
                return el;
              })}
              {i % everyNthLabel === 0 && (
                <text x={x + barW / 2} y={H - 6} className="tick" textAnchor="middle">{formatBucket(b.key)}</text>
              )}
            </g>
          );
        })}
      </svg>
      {hover != null && (
        <Tooltip x={PAD.left + band * hover + band / 2} width={width}>
          <strong>{formatBucket(buckets[hover].key, true)}</strong>
          {series.filter((k) => buckets[hover].values[k]).map((k) => (
            <div key={k} className="tt-row"><span className="swatch" style={{ background: colors[k] }} />{labels[k] ?? k}<span className="tt-v">{buckets[hover].values[k]}</span></div>
          ))}
          <div className="tt-row tt-total">Total<span className="tt-v">{series.reduce((s, k) => s + (buckets[hover].values[k] ?? 0), 0)}</span></div>
        </Tooltip>
      )}
    </div>
  );
}

function roundedTop(x, y, w, h, r) {
  if (h <= 0) return '';
  const rr = Math.min(r, h, w / 2);
  return `M${x},${y + h} V${y + rr} Q${x},${y} ${x + rr},${y} H${x + w - rr} Q${x + w},${y} ${x + w},${y + rr} V${y + h} Z`;
}

/** Single-series line with a 10% area wash, crosshair and tooltip. */
export function LineArea({ points, formatBucket, color = 'var(--series-1)', label, everyNthLabel = 6 }) {
  const [setRef, width] = useWidth();
  const [hover, setHover] = useState(null);
  const inner = width - PAD.left - PAD.right;
  const { max, step } = niceScale(Math.max(0, ...points.map((p) => p.value)));
  const plotH = H - PAD.top - PAD.bottom;
  const x = (i) => PAD.left + (points.length <= 1 ? inner / 2 : (inner * i) / (points.length - 1));
  const y = (v) => PAD.top + plotH * (1 - v / max);
  const line = points.map((p, i) => `${i ? 'L' : 'M'}${x(i)},${y(p.value)}`).join(' ');
  const area = points.length ? `${line} L${x(points.length - 1)},${H - PAD.bottom} L${x(0)},${H - PAD.bottom} Z` : '';

  function move(e) {
    const rect = e.currentTarget.getBoundingClientRect();
    const i = Math.round(((e.clientX - rect.left - PAD.left) / inner) * (points.length - 1));
    setHover(Math.max(0, Math.min(points.length - 1, i)));
  }

  return (
    <div className="chart" ref={setRef}>
      <svg width={width} height={H} onMouseMove={move} onMouseLeave={() => setHover(null)} role="img" aria-label={label}>
        <Axis width={width} max={max} step={step} />
        <path d={area} fill={color} opacity="0.1" />
        <path d={line} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
        {points.map((p, i) => i % everyNthLabel === 0 && (
          <text key={p.key} x={x(i)} y={H - 6} className="tick" textAnchor="middle">{formatBucket(p.key)}</text>
        ))}
        {hover != null && (
          <g>
            <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={H - PAD.bottom} className="crosshair" />
            <circle cx={x(hover)} cy={y(points[hover].value)} r="4" fill={color} stroke="var(--surface-1)" strokeWidth="2" />
          </g>
        )}
      </svg>
      {hover != null && (
        <Tooltip x={x(hover)} width={width}>
          <strong>{formatBucket(points[hover].key, true)}</strong>
          <div className="tt-row">{label}<span className="tt-v">{points[hover].value}</span></div>
        </Tooltip>
      )}
    </div>
  );
}

function Tooltip({ x, width, children }) {
  const left = Math.min(Math.max(8, x + 12), width - 180);
  return <div className="tooltip" style={{ left }}>{children}</div>;
}

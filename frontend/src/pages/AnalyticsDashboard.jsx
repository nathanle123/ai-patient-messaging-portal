import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { CATEGORY_LABEL, URGENCY_LABEL, timeAgo } from '../labels.js';
import {
  BarList, CATEGORY_COLORS, ChartFrame, DataTable, Legend, LineArea, StackedColumns, URGENCY_COLORS, compact,
} from '../components/charts.jsx';

const SOURCES = [
  ['all', 'All sources'],
  ['portalpal', 'PortalPal dataset'],
  ['synthetic', 'Synthetic (labelled)'],
  ['manual', 'Portal UI'],
];
const CATEGORY_KEYS = ['symptoms', 'medication_refill', 'scheduling', 'test_results', 'billing', 'other', 'unclassified'];
const URGENCY_KEYS = ['urgent', 'soon', 'routine', 'unclassified'];
const CAT_LABELS = { ...CATEGORY_LABEL, unclassified: 'Unclassified' };
const URG_LABELS = { ...URGENCY_LABEL, unclassified: 'Unclassified' };
const SERVICE_LABELS = {
  'phi-detection': 'PHI detection', urgency: 'Urgency', routing: 'Routing', summarization: 'Summarization', search: 'Similarity search',
  fhir: 'FHIR (mock)',
};

const hourLabel = (iso, long) => {
  const d = new Date(iso);
  return long ? d.toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' })
    : d.toLocaleTimeString([], { hour: 'numeric' });
};
const dayLabel = (iso, long) => {
  const d = new Date(`${iso}T00:00:00Z`);
  return d.toLocaleDateString([], { timeZone: 'UTC', month: 'short', day: 'numeric', ...(long ? { weekday: 'short' } : {}) });
};
const ms = (v) => (v == null ? '–' : v >= 1000 ? `${(v / 1000).toFixed(2)} s` : `${v} ms`);
const pct = (v) => (v == null ? '–' : `${(v * 100).toFixed(v > 0 && v < 0.01 ? 1 : 0)}%`);

function usePoll(path, everyMs) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const load = useCallback(async () => {
    try { setData(await api(path)); setError(null); } catch (e) { setError(e.message); }
  }, [path]);
  useEffect(() => {
    load();
    const t = setInterval(load, everyMs);
    return () => clearInterval(t);
  }, [load, everyMs]);
  return [data, error, load];
}

function Stat({ label, value, note, emphasis }) {
  return (
    <div className={`stat ${emphasis ? 'stat-hero' : ''}`}>
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
      {note && <span className="muted small">{note}</span>}
    </div>
  );
}

function HealthBadge({ health }) {
  const icon = { up: '●', degraded: '▲', down: '✕', unknown: '?' }[health] ?? '?';
  return <span className={`health health-${health}`}><span aria-hidden="true">{icon}</span> {health}</span>;
}

export default function AnalyticsDashboard() {
  const [source, setSource] = useState('all');
  const [summary, summaryError] = usePoll(`/api/admin/analytics/summary?source=${source}`, 5000);
  const [hourly, , reloadHourly] = usePoll(`/api/admin/analytics/trends?granularity=hourly&hours=48&source=${source}`, 30000);
  const [daily, , reloadDaily] = usePoll(`/api/admin/analytics/trends?granularity=daily&days=14&source=${source}`, 30000);
  const [tech] = usePoll('/api/admin/analytics/technical', 5000);
  const [running, setRunning] = useState(false);

  async function aggregateNow() {
    setRunning(true);
    try { await api('/api/admin/analytics/aggregate', { method: 'POST' }); await Promise.all([reloadHourly(), reloadDaily()]); }
    finally { setRunning(false); }
  }

  const t = summary?.totals;
  const sourceCount = (k) => (k === 'all' ? Object.values(summary?.sourceCounts ?? {}).reduce((a, b) => a + b, 0) : summary?.sourceCounts?.[k] ?? 0);
  const dailyBy = (field) => (daily?.buckets ?? []).map((b) => ({ key: b.bucket, values: b[field] }));

  return (
    <>
      <section className="card">
        <div className="card-head">
          <h2>Analytics</h2>
          <span className="spacer" />
          <div className="segmented" role="radiogroup" aria-label="Message source">
            {SOURCES.map(([k, label]) => (
              <button key={k} role="radio" aria-checked={source === k} className={source === k ? 'active' : ''} onClick={() => setSource(k)}>
                {label} <span className="count">{sourceCount(k)}</span>
              </button>
            ))}
          </div>
        </div>
        <p className="muted small">
          Counts and timings only; no message text is used. Urgency labels are demonstration-only review flags, not validated
          clinical triage. <em>Synthetic</em> messages are labelled template messages added because the PortalPal datasets are
          almost all symptom questions; filter them out to see dataset-only numbers.
        </p>
        {summaryError && <p className="error">{summaryError}</p>}
        {t && (
          <div className="stats">
            <Stat emphasis label="Unreviewed urgent" value={t.unreviewedUrgent} note="flagged urgent, not yet reviewed" />
            <Stat label="Messages" value={compact(t.messages)} note={`${t.processed} processed · ${t.inFlight} in flight`} />
            <Stat label="Unreviewed" value={compact(t.unreviewed)} note={`${t.reviewed} reviewed`} />
            <Stat label="Redaction failed" value={t.redactionFailed} note="never published" />
            <Stat label="Median time to review" value={summary.review.medianMinutesToReview == null ? '–' : `${summary.review.medianMinutesToReview.toFixed(1)} min`}
                  note={`p90 ${summary.review.p90MinutesToReview == null ? '–' : `${summary.review.p90MinutesToReview.toFixed(1)} min`} · from publish`} />
          </div>
        )}
      </section>

      {summary && (
        <div className="dash-grid">
          <ChartFrame title="Volume by category" subtitle="Live, from routing results"
                      table={<DataTable columns={[{ key: 'k', label: 'Category' }, { key: 'v', label: 'Messages', num: true }]}
                                        rows={CATEGORY_KEYS.map((k) => ({ k: CAT_LABELS[k], v: summary.byCategory[k] ?? 0 }))} />}>
            <BarList data={summary.byCategory} keys={CATEGORY_KEYS} colors={CATEGORY_COLORS} labels={CAT_LABELS} />
          </ChartFrame>
          <ChartFrame title="Volume by urgency flag" subtitle="Demonstration-only review flags"
                      table={<DataTable columns={[{ key: 'k', label: 'Urgency' }, { key: 'v', label: 'Messages', num: true }]}
                                        rows={URGENCY_KEYS.map((k) => ({ k: URG_LABELS[k], v: summary.byUrgency[k] ?? 0 }))} />}>
            <BarList data={summary.byUrgency} keys={URGENCY_KEYS} colors={URGENCY_COLORS} labels={URG_LABELS} />
          </ChartFrame>
        </div>
      )}

      <section className="card">
        <div className="card-head">
          <h2>Historical trends</h2>
          <span className="spacer" />
          <span className="muted small">
            Scheduled aggregation (hourly every 15 min, daily nightly, UTC buckets) · last run {hourly?.aggregatedAt ? timeAgo(hourly.aggregatedAt) : '–'}
          </span>
          <button className="btn btn-quiet small" onClick={aggregateNow} disabled={running}>{running ? 'Aggregating…' : 'Run now'}</button>
        </div>
      </section>
      {hourly && (
        <ChartFrame title="Hourly message volume" subtitle="Last 48 hours, by submission time"
                    table={<DataTable columns={[{ key: 'h', label: 'Hour' }, { key: 'v', label: 'Messages', num: true }]}
                                      rows={hourly.buckets.filter((b) => b.total).map((b) => ({ h: hourLabel(b.bucket, true), v: b.total }))} />}>
          <LineArea points={hourly.buckets.map((b) => ({ key: b.bucket, value: b.total }))} formatBucket={hourLabel} label="Messages" />
        </ChartFrame>
      )}
      {daily && (
        <div className="dash-grid">
          <ChartFrame title="Daily volume by urgency flag" subtitle="Last 14 days"
                      legend={<Legend keys={URGENCY_KEYS} colors={URGENCY_COLORS} labels={URG_LABELS} />}
                      table={<DataTable columns={[{ key: 'd', label: 'Day' }, ...URGENCY_KEYS.map((k) => ({ key: k, label: URG_LABELS[k], num: true }))]}
                                        rows={daily.buckets.map((b) => ({ d: dayLabel(b.bucket, true), ...b.byUrgency }))} />}>
            <StackedColumns buckets={dailyBy('byUrgency')} series={URGENCY_KEYS} colors={URGENCY_COLORS} labels={URG_LABELS} formatBucket={dayLabel} everyNthLabel={2} />
          </ChartFrame>
          <ChartFrame title="Daily volume by category" subtitle="Last 14 days"
                      legend={<Legend keys={CATEGORY_KEYS.slice(0, 6)} colors={CATEGORY_COLORS} labels={CAT_LABELS} />}
                      table={<DataTable columns={[{ key: 'd', label: 'Day' }, ...CATEGORY_KEYS.map((k) => ({ key: k, label: CAT_LABELS[k], num: true }))]}
                                        rows={daily.buckets.map((b) => ({ d: dayLabel(b.bucket, true), ...b.byCategory }))} />}>
            <StackedColumns buckets={dailyBy('byCategory')} series={CATEGORY_KEYS} colors={CATEGORY_COLORS} labels={CAT_LABELS} formatBucket={dayLabel} everyNthLabel={2} />
          </ChartFrame>
        </div>
      )}

      {tech && (
        <section className="card">
          <div className="card-head">
            <h2>Technical</h2>
            <span className="spacer" />
            <span className="muted small">Live from service.events (last 60 min) · health checked every 10 s</span>
          </div>
          <div className="stats">
            <Stat label="Throughput" value={`${tech.pipeline.completedPerMinute.toFixed(1)}/min`} note={`${tech.pipeline.completedLast15m} messages completed in 15 min`} />
            <Stat label="End-to-end latency" value={tech.pipeline.e2eP50Seconds == null ? '–' : `${tech.pipeline.e2eP50Seconds.toFixed(1)} s`}
                  note={`p50 publish → 4th result · p95 ${tech.pipeline.e2eP95Seconds == null ? '–' : `${tech.pipeline.e2eP95Seconds.toFixed(1)} s`}`} />
            <Stat label="In flight" value={tech.pipeline.inFlight} note="published, awaiting AI results" />
            <Stat label="Dead-lettered" value={Object.values(tech.dlqDepth).reduce((a, b) => a + b, 0)}
                  note={tech.kafkaAvailable ? 'records across DLQ topics' : 'Kafka unavailable'} />
            {tech.fhirWriteBack && (
              <Stat label="FHIR write-back" value={tech.fhirWriteBack.written}
                    note={`Communications written · ${tech.fhirWriteBack.pending} pending · ${tech.fhirWriteBack.rejected} rejected`} />
            )}
          </div>
          <DataTable
            columns={[
              { key: 'service', label: 'Service' }, { key: 'health', label: 'Health' }, { key: 'perMin', label: 'Events/min (5 min)', num: true },
              { key: 'p50', label: 'p50 latency', num: true }, { key: 'p95', label: 'p95 latency', num: true },
              { key: 'errors', label: 'Errors (60 min)', num: true }, { key: 'lag', label: 'Consumer lag', num: true },
            ]}
            rows={tech.services.map((s) => ({
              service: SERVICE_LABELS[s.service] ?? s.service,
              health: <HealthBadge health={s.health} />,
              perMin: s.live ? s.live.perMinute.toFixed(1) : '–',
              p50: ms(s.live?.p50LatencyMs),
              p95: ms(s.live?.p95LatencyMs),
              errors: s.live ? `${s.live.failuresInWindow} (${pct(s.live.errorRate)})` : '–',
              lag: s.consumerLag ?? (s.service === 'phi-detection' || s.service === 'fhir' ? 'sync HTTP' : '–'),
            }))}
          />
          {Object.values(tech.dlqDepth).some((v) => v > 0) && (
            <p className="muted small">DLQ depth: {Object.entries(tech.dlqDepth).filter(([, v]) => v).map(([k, v]) => `${k} ${v}`).join(' · ')}</p>
          )}
        </section>
      )}
    </>
  );
}

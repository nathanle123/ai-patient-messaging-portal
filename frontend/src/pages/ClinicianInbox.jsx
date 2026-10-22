import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { CATEGORY_LABEL, timeAgo } from '../labels.js';
import UrgencyBadge from '../components/UrgencyBadge.jsx';
import MessageDetail from './MessageDetail.jsx';

const VIEWS = [
  ['UNREVIEWED', 'Unreviewed'],
  ['REVIEWED', 'Reviewed'],
  ['ALL', 'All'],
];
const REFRESH_MS = 5000;

export default function ClinicianInbox() {
  const [view, setView] = useState('UNREVIEWED');
  const [data, setData] = useState(null);
  const [selected, setSelected] = useState(null);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    try {
      setData(await api(`/api/clinician/inbox?view=${view}&size=100`));
      setError(null);
    } catch (err) {
      setError(err.message);
    }
  }, [view]);

  useEffect(() => {
    load();
    const t = setInterval(load, REFRESH_MS); // AI results arrive asynchronously
    return () => clearInterval(t);
  }, [load]);

  const counts = data?.counts;
  return (
    <div className="inbox">
      <section className="card inbox-list">
        <div className="card-head">
          <h2>Inbox</h2>
          <span className="spacer" />
          {counts && counts.unreviewedUrgent > 0 && (
            <span className="badge badge-urgent">{counts.unreviewedUrgent} urgent unreviewed</span>
          )}
        </div>
        <div className="tabs" role="tablist">
          {VIEWS.map(([key, label]) => (
            <button key={key} role="tab" aria-selected={view === key}
                    className={`tab ${view === key ? 'active' : ''}`} onClick={() => setView(key)}>
              {label}
              {counts && key === 'UNREVIEWED' && <span className="count">{counts.unreviewed}</span>}
              {counts && key === 'REVIEWED' && <span className="count">{counts.reviewed}</span>}
            </button>
          ))}
        </div>
        <p className="muted small">Sorted unreviewed first, then urgent → soon → routine, then oldest first. Urgency labels are demonstration-only review flags.</p>
        {error && <p className="error">{error}</p>}
        <ul className="plain-list">
          {data?.items.map((m) => (
            <li key={m.id}>
              <button className={`inbox-item ${selected === m.id ? 'selected' : ''} ${m.reviewedAt ? 'reviewed' : ''}`}
                      onClick={() => setSelected(m.id)}>
                <div className="row">
                  <UrgencyBadge label={m.urgencyLabel} failed={m.status === 'REDACTION_FAILED'} />
                  {m.category && <span className="tag">{CATEGORY_LABEL[m.category]}</span>}
                  {m.source === 'synthetic' && <span className="tag tag-source" title="Labelled synthetic template message, not dataset text">synthetic</span>}
                  <span className="spacer" />
                  <span className="muted small" title={new Date(m.submittedAt).toLocaleString()}>{timeAgo(m.submittedAt)}</span>
                </div>
                <div className="inbox-patient">{m.patientName}</div>
                <div className="inbox-summary">{m.summary || m.preview || <em className="muted">Redaction failed. Open to view the original.</em>}</div>
                {m.status === 'PUBLISHED' && <div className="muted small">AI results {m.resultCount}/4…</div>}
              </button>
            </li>
          ))}
          {data && data.items.length === 0 && <li className="muted">Nothing here.</li>}
        </ul>
      </section>
      <section className="inbox-detail">
        {selected ? <MessageDetail id={selected} onReviewed={load} />
          : <div className="card muted">Select a message.</div>}
      </section>
    </div>
  );
}

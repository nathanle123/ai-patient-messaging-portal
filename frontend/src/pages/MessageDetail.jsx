import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { CATEGORY_LABEL, URGENCY_LABEL, timeAgo } from '../labels.js';
import UrgencyBadge from '../components/UrgencyBadge.jsx';

function Highlighted({ text, sentences }) {
  // Marks the summary's sentences inside the redacted message using their offsets.
  if (!sentences?.length) return <p className="message-text">{text}</p>;
  const parts = [];
  let cursor = 0;
  [...sentences].sort((a, b) => a.start - b.start).forEach((s, i) => {
    if (s.start > cursor) parts.push(text.slice(cursor, s.start));
    parts.push(<mark key={i}>{text.slice(s.start, s.end)}</mark>);
    cursor = s.end;
  });
  parts.push(text.slice(cursor));
  return <p className="message-text">{parts}</p>;
}

function ScoreBars({ scores, chosen }) {
  const entries = Object.entries(scores || {}).sort((a, b) => b[1] - a[1]);
  return (
    <ul className="plain-list bars">
      {entries.map(([k, v]) => (
        <li key={k} className={k === chosen ? 'chosen' : ''}>
          <span className="bar-label">{CATEGORY_LABEL[k]}</span>
          <span className="bar"><span style={{ width: `${Math.round(v * 100)}%` }} /></span>
          <span className="bar-value">{v.toFixed(2)}</span>
        </li>
      ))}
    </ul>
  );
}

export default function MessageDetail({ id, onReviewed }) {
  const [m, setM] = useState(null);
  const [original, setOriginal] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try { setM(await api(`/api/clinician/messages/${id}`)); setError(null); } catch (e) { setError(e.message); }
  }, [id]);

  useEffect(() => {
    setOriginal(null);
    load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, [load]);

  async function showOriginal() {
    setOriginal((await api(`/api/clinician/messages/${id}/original`)).originalText);
  }

  async function review() {
    setBusy(true);
    try { setM(await api(`/api/clinician/messages/${id}/review`, { method: 'POST' })); onReviewed?.(); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }

  if (error) return <div className="card error">{error}</div>;
  if (!m) return <div className="card muted">Loading…</div>;

  const urgency = m.results.urgency;
  const routing = m.results.routing;
  const summary = m.results.summarization;
  const failed = m.status === 'REDACTION_FAILED';

  return (
    <div className="detail">
      <section className="card">
        <div className="card-head">
          <h2>{m.patientName}</h2>
          <span className="spacer" />
          {m.reviewedAt
            ? <span className="ok small">Reviewed by {m.reviewedBy} · {timeAgo(m.reviewedAt)}</span>
            : <button className="btn" onClick={review} disabled={busy}>Mark reviewed</button>}
        </div>
        <div className="row">
          <UrgencyBadge label={m.urgencyLabel} failed={failed} />
          {m.category && <span className="tag">{CATEGORY_LABEL[m.category]}</span>}
          {m.source !== 'manual' && <span className="tag tag-source" title="Seeded message source">source: {m.source}</span>}
          <span className="muted small">{new Date(m.submittedAt).toLocaleString()}</span>
          {m.phiEntityCount != null && <span className="muted small">· {m.phiEntityCount} PHI item(s) redacted</span>}
          {m.fhir?.patientId && (
            <span className="muted small" title="FHIR R4 linkage (mock server). The Communication holds the redacted text only.">
              · FHIR Patient/{m.fhir.patientId}
              {m.fhir.status === 'WRITTEN' && <> · Communication/{m.fhir.communicationId.slice(0, 8)}</>}
              {m.fhir.status === 'REJECTED' && <> · write-back rejected</>}
              {m.fhir.status == null && !failed && <> · write-back pending</>}
            </span>
          )}
        </div>
        {summary?.summary && (
          <div className="summary"><span className="muted small">Summary (extracted verbatim)</span><p>{summary.summary}</p></div>
        )}
        <h3>Message {failed ? '' : '(redacted)'}</h3>
        {failed ? <p className="muted">PHI redaction failed, so this message was not sent to the AI services.</p>
          : <Highlighted text={m.redactedText} sentences={summary?.sentences} />}
        <div className="original">
          {original == null
            ? <button className="btn btn-quiet" onClick={showOriginal}>Show original text</button>
            : <><h3>Original</h3><p className="message-text original-text">{original}</p></>}
          <span className="muted small">Viewing the original is recorded in the audit log.</span>
        </div>
      </section>

      {!failed && (
        <div className="result-grid">
          <section className="card">
            <h3>Urgency flag</h3>
            <p className="muted small">Demonstration-only review flag. Not validated clinical triage.</p>
            {urgency ? (
              <>
                <p><UrgencyBadge label={urgency.label} /> <span className="muted small">rules {urgency.ruleset_version}</span></p>
                {urgency.flags.length === 0 && <p className="muted small">No red-flag phrases found.</p>}
                <ul className="plain-list flags">
                  {urgency.flags.map((f, i) => (
                    <li key={i} className={f.negated ? 'negated' : ''}>
                      <span className={`tier tier-${f.tier.toLowerCase()}`}>{URGENCY_LABEL[f.tier === 'URGENT' ? 'urgent' : 'soon']}</span>
                      “{f.phrase}” {f.negated && <span className="muted small">negated</span>}
                      {f.note && <span className="muted small"> · {f.note}</span>}
                    </li>
                  ))}
                </ul>
              </>
            ) : <p className="muted">Pending…</p>}
          </section>
          <section className="card">
            <h3>Routing</h3>
            {routing ? (
              <>
                <p>{CATEGORY_LABEL[routing.category]} <span className="muted small">confidence {routing.confidence.toFixed(2)}</span></p>
                <ScoreBars scores={routing.scores} chosen={routing.category} />
              </>
            ) : <p className="muted">Pending…</p>}
          </section>
          <section className="card wide">
            <h3>Similar messages</h3>
            <p className="muted small">Cosine similarity ≥ {m.similarityThreshold.toFixed(2)} (threshold tuned on seeded data)</p>
            {!m.results.search && <p className="muted">Pending…</p>}
            {m.results.search && m.similar.length === 0 && <p className="muted small">No sufficiently similar earlier messages.</p>}
            <ul className="plain-list">
              {m.similar.map((s) => (
                <li key={s.id} className="similar">
                  <span className="score">{s.score.toFixed(2)}</span>
                  <UrgencyBadge label={s.urgencyLabel} />
                  {s.category && <span className="tag">{CATEGORY_LABEL[s.category]}</span>}
                  <span className="small">{s.summary}</span>
                </li>
              ))}
            </ul>
          </section>
        </div>
      )}
    </div>
  );
}

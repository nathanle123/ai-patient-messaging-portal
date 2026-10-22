import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import AnalyticsDashboard from './AnalyticsDashboard.jsx';

const ACTIONS = ['', 'LOGIN_SUCCESS', 'LOGIN_FAILURE', 'ACCESS_DENIED', 'VIEW_AUDIT_LOG',
  'VIEW_ORIGINAL_MESSAGE', 'REVIEW_MESSAGE'];
const PAGE_SIZE = 25;

function AuditLog() {
  const [action, setAction] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const qs = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
      if (action) qs.set('action', action);
      setData(await api(`/api/admin/audit-log?${qs}`));
    } catch (err) {
      setError(err.message);
    }
  }, [action, page]);

  useEffect(() => { load(); }, [load]);

  const totalPages = data ? Math.max(1, Math.ceil(data.totalItems / PAGE_SIZE)) : 1;

  return (
    <section className="card">
      <div className="card-head">
        <h2>Audit log</h2>
        <span className="spacer" />
        <select value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }} aria-label="Filter by action">
          {ACTIONS.map((a) => <option key={a} value={a}>{a || 'All actions'}</option>)}
        </select>
        <button className="btn btn-quiet" onClick={load}>Refresh</button>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Time</th><th>Actor</th><th>Role</th><th>Action</th><th>Target</th></tr>
          </thead>
          <tbody>
            {data?.items.map((e) => (
              <tr key={e.id}>
                <td className="nowrap">{new Date(e.occurredAt).toLocaleString()}</td>
                <td>{e.actorUsername ?? '—'}</td>
                <td>{e.actorRole ?? '—'}</td>
                <td><span className={`tag tag-${e.action.toLowerCase()}`}>{e.action}</span></td>
                <td className="mono small">{[e.targetType, e.targetId].filter(Boolean).join(' ') || '—'}</td>
              </tr>
            ))}
            {data && data.items.length === 0 && <tr><td colSpan={5} className="muted">No entries.</td></tr>}
          </tbody>
        </table>
      </div>
      <div className="pager">
        <button className="btn btn-quiet" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
        <span className="muted small">Page {page + 1} of {totalPages} · {data?.totalItems ?? 0} entries</span>
        <button className="btn btn-quiet" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </section>
  );
}

export default function AdminDashboard() {
  return (
    <>
      <AnalyticsDashboard />
      <AuditLog />
    </>
  );
}

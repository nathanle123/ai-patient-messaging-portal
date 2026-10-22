import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { PATIENT_STATUS_LABEL } from '../labels.js';

const MAX = 5000;

export default function PatientPortal() {
  const [text, setText] = useState('');
  const [messages, setMessages] = useState([]);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);

  const load = useCallback(async () => {
    try { setMessages(await api('/api/messages')); } catch { /* shown as empty */ }
  }, []);
  useEffect(() => { load(); }, [load]);

  async function send(e) {
    e.preventDefault();
    setBusy(true);
    setNotice(null);
    try {
      await api('/api/messages', { method: 'POST', body: { text } });
      setText('');
      setNotice({ ok: true, text: 'Message sent to your care team.' });
      load();
    } catch {
      setNotice({ ok: false, text: 'Could not send your message. Please try again.' });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <form className="card" onSubmit={send}>
        <h2>Send a message to your care team</h2>
        <p className="muted small">For emergencies, call 911. Messages are not monitored in real time.</p>
        <textarea rows={6} value={text} maxLength={MAX} onChange={(e) => setText(e.target.value)}
                  placeholder="Describe your question or concern…" required />
        <div className="row">
          <button className="btn" disabled={busy || !text.trim()}>{busy ? 'Sending…' : 'Send'}</button>
          <span className="muted small">{text.length}/{MAX}</span>
          {notice && <span className={notice.ok ? 'ok small' : 'error small'} role="status">{notice.text}</span>}
        </div>
      </form>
      <section className="card">
        <h2>My messages</h2>
        {messages.length === 0 && <p className="muted">No messages yet.</p>}
        <ul className="plain-list">
          {messages.map((m) => (
            <li key={m.id} className="my-message">
              <div className="row">
                <span className="muted small">{new Date(m.submittedAt).toLocaleString()}</span>
                <span className="tag">{PATIENT_STATUS_LABEL[m.status]}</span>
              </div>
              <p className="message-text">{m.text}</p>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}

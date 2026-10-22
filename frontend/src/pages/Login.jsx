import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../AuthContext.jsx';
import DemoBanner from '../components/DemoBanner.jsx';
import { homeFor } from '../session.js';

const DEMO_ACCOUNTS = [
  { username: 'maria.lopez', role: 'Patient' },
  { username: 'dr.alvarez', role: 'Clinician' },
  { username: 'admin', role: 'Admin' },
];

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const user = await login(username.trim(), password);
      navigate(homeFor(user.role), { replace: true });
    } catch (err) {
      setError(err.status === 401 ? 'Invalid username or password.' : 'Sign-in failed. Is the platform running?');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app">
      <DemoBanner />
      <div className="login-wrap">
        <form className="card login-card" onSubmit={submit}>
          <h1>Portal Messaging</h1>
          <p className="muted">Sign in with a demo account.</p>
          <label>
            Username
            <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
          </label>
          <label>
            Password
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                   autoComplete="current-password" required />
          </label>
          {error && <p className="error" role="alert">{error}</p>}
          <button className="btn" type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
          <div className="demo-accounts">
            <p className="muted small">Demo accounts (password is <code>DEMO_USER_PASSWORD</code> in your <code>.env</code>):</p>
            <ul>
              {DEMO_ACCOUNTS.map((a) => (
                <li key={a.username}>
                  <button type="button" className="link" onClick={() => setUsername(a.username)}>{a.username}</button>
                  <span className="muted small"> · {a.role}</span>
                </li>
              ))}
            </ul>
            <p className="muted small">Every row in <code>fake_phi.csv</code> is a patient account, e.g. <code>james.carter</code>.</p>
          </div>
        </form>
      </div>
    </div>
  );
}

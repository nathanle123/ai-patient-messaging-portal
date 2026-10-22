import { useAuth } from '../AuthContext.jsx';
import DemoBanner from './DemoBanner.jsx';

const ROLE_LABEL = { PATIENT: 'Patient', CLINICIAN: 'Clinician', ADMIN: 'Admin' };

export default function Layout({ children }) {
  const { user, logout } = useAuth();
  return (
    <div className="app">
      <DemoBanner />
      <header className="topbar">
        <span className="brand">Portal Messaging</span>
        <span className="spacer" />
        <span className="who">
          {user.displayName}
          <span className={`role-badge role-${user.role.toLowerCase()}`}>{ROLE_LABEL[user.role]}</span>
        </span>
        <button className="btn btn-quiet" onClick={logout}>Sign out</button>
      </header>
      <main className="content">{children}</main>
    </div>
  );
}

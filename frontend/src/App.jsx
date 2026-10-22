import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './AuthContext.jsx';
import Layout from './components/Layout.jsx';
import Login from './pages/Login.jsx';
import PatientPortal from './pages/PatientPortal.jsx';
import ClinicianInbox from './pages/ClinicianInbox.jsx';
import AdminDashboard from './pages/AdminDashboard.jsx';
import { homeFor } from './session.js';

// Route guards are a convenience only; the platform enforces every role check server-side.
function RequireRole({ role, children }) {
  const { user } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  if (user.role !== role) return <Navigate to={homeFor(user.role)} replace />;
  return <Layout>{children}</Layout>;
}

export default function App() {
  const { user } = useAuth();
  return (
    <Routes>
      <Route path="/login" element={user ? <Navigate to={homeFor(user.role)} replace /> : <Login />} />
      <Route path="/patient" element={<RequireRole role="PATIENT"><PatientPortal /></RequireRole>} />
      <Route path="/inbox" element={<RequireRole role="CLINICIAN"><ClinicianInbox /></RequireRole>} />
      <Route path="/admin" element={<RequireRole role="ADMIN"><AdminDashboard /></RequireRole>} />
      <Route path="*" element={<Navigate to={user ? homeFor(user.role) : '/login'} replace />} />
    </Routes>
  );
}

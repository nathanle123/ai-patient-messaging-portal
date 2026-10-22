import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { api, configureApi } from './api.js';
import { clearSession, loadSession, saveSession } from './session.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [session, setSession] = useState(() => {
    const s = loadSession();
    configureApi({ token: s?.token });
    return s;
  });

  const logout = useCallback(() => {
    clearSession();
    configureApi({ token: null });
    setSession(null);
  }, []);

  configureApi({ token: session?.token, unauthorized: logout });

  const login = useCallback(async (username, password) => {
    const res = await api('/api/auth/login', {
      method: 'POST',
      body: { username, password },
      auth: false,
    });
    saveSession(res);
    configureApi({ token: res.token });
    setSession(res);
    return res.user;
  }, []);

  const value = useMemo(
    () => ({ user: session?.user ?? null, login, logout }),
    [session, login, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}

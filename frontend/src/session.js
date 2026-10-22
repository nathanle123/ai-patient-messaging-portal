// Token storage and role routing. sessionStorage keeps the token out of other tabs and drops it when the
// tab closes; see docs/design-notes.md §7 for the XSS trade-off.

const KEY = 'portal.session';

export const HOME_BY_ROLE = {
  PATIENT: '/patient',
  CLINICIAN: '/inbox',
  ADMIN: '/admin',
};

export function homeFor(role) {
  return HOME_BY_ROLE[role] ?? '/login';
}

export function loadSession(storage = globalThis.sessionStorage, now = Date.now()) {
  try {
    const raw = storage?.getItem(KEY);
    if (!raw) return null;
    const session = JSON.parse(raw);
    if (!session?.token || !session?.user?.role || Date.parse(session.expiresAt) <= now) {
      storage.removeItem(KEY);
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

export function saveSession(session, storage = globalThis.sessionStorage) {
  try {
    storage?.setItem(KEY, JSON.stringify(session));
  } catch {
    // Storage unavailable (private mode): the session lasts until reload.
  }
}

export function clearSession(storage = globalThis.sessionStorage) {
  try {
    storage?.removeItem(KEY);
  } catch {
    // ignore
  }
}

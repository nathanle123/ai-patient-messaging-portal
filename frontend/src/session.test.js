import { describe, expect, it } from 'vitest';
import { clearSession, homeFor, loadSession, saveSession } from './session.js';

function memoryStorage() {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
  };
}

const session = (expiresAt) => ({
  token: 't',
  expiresAt,
  user: { id: '1', username: 'maria.lopez', role: 'PATIENT', displayName: 'Maria Lopez' },
});

describe('homeFor', () => {
  it('routes each role to its view', () => {
    expect(homeFor('PATIENT')).toBe('/patient');
    expect(homeFor('CLINICIAN')).toBe('/inbox');
    expect(homeFor('ADMIN')).toBe('/admin');
    expect(homeFor('SOMETHING_ELSE')).toBe('/login');
  });
});

describe('session storage', () => {
  it('round-trips an unexpired session', () => {
    const s = memoryStorage();
    saveSession(session('2030-01-01T00:00:00Z'), s);
    expect(loadSession(s, Date.parse('2029-01-01T00:00:00Z'))?.user.username).toBe('maria.lopez');
  });

  it('drops an expired session', () => {
    const s = memoryStorage();
    saveSession(session('2020-01-01T00:00:00Z'), s);
    expect(loadSession(s, Date.parse('2026-01-01T00:00:00Z'))).toBeNull();
    expect(s.getItem('portal.session')).toBeNull();
  });

  it('ignores corrupt data and survives clearing', () => {
    const s = memoryStorage();
    s.setItem('portal.session', '{not json');
    expect(loadSession(s)).toBeNull();
    clearSession(s);
    expect(loadSession(s)).toBeNull();
  });
});

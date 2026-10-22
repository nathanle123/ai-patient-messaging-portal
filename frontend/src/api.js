let currentToken = null;
let onUnauthorized = () => {};

export function configureApi({ token, unauthorized }) {
  currentToken = token ?? null;
  if (unauthorized) onUnauthorized = unauthorized;
}

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `Request failed (${status})`);
    this.status = status;
    this.body = body;
  }
}

export async function api(path, { method = 'GET', body, auth = true } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && currentToken) headers.Authorization = `Bearer ${currentToken}`;

  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) {
    if (res.status === 401 && auth) onUnauthorized();
    throw new ApiError(res.status, data);
  }
  return data;
}

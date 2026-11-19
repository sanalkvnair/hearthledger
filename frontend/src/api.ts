export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public errors?: { row: number; message: string }[]) {
    super(message);
  }
}

async function send(path: string, method: string, body?: unknown): Promise<Response> {
  return fetch(`/api/v1${path}`, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    credentials: 'same-origin',
  });
}

let refreshing: Promise<boolean> | null = null;

function refreshSession(): Promise<boolean> {
  refreshing ??= fetch('/api/v1/auth/refresh', { method: 'POST' })
    .then((r) => r.ok)
    .finally(() => {
      refreshing = null;
    });
  return refreshing;
}

export async function api<T = void>(path: string, method = 'GET', body?: unknown): Promise<T> {
  let res = await send(path, method, body);
  if (res.status === 401 && !path.startsWith('/auth') && (await refreshSession())) {
    res = await send(path, method, body);
  }
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new ApiError(res.status, problem.code ?? 'ERROR', problem.detail ?? res.statusText, problem.errors);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export async function upload<T>(path: string, form: FormData): Promise<T> {
  const post = () => fetch(`/api/v1${path}`, { method: 'POST', body: form, credentials: 'same-origin' });
  let res = await post();
  if (res.status === 401 && (await refreshSession())) res = await post();
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new ApiError(res.status, problem.code ?? 'ERROR', problem.detail ?? res.statusText);
  }
  return res.json();
}

export async function download(path: string, filename: string): Promise<void> {
  const res = await fetch(`/api/v1${path}`, { credentials: 'same-origin' });
  if (!res.ok) throw new ApiError(res.status, 'ERROR', res.statusText);
  const url = URL.createObjectURL(await res.blob());
  const a = Object.assign(document.createElement('a'), { href: url, download: filename });
  a.click();
  URL.revokeObjectURL(url);
}

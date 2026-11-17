let csrf

export class ApiError extends Error {
  constructor(status, data) {
    super(data?.message || (status >= 500 ? '系统暂不可用，请稍后重试' : '操作未完成，请重试'))
    this.status = status
    this.code = data?.code
  }
}

export async function api(path, options = {}) {
  const method = options.method || 'GET'
  const headers = new Headers(options.headers)
  if (method !== 'GET') {
    if (!csrf) csrf = (await api('/api/csrf')).token
    headers.set('X-CSRF-TOKEN', csrf)
  }
  if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    cache: 'no-store',
  })
  const type = response.headers.get('content-type') || ''
  const data = type.includes('application/json') ? await response.json() : null
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) csrf = undefined
    if (response.status === 401 && data?.code === 'AUTH_REQUIRED' && !path.endsWith('/me')) {
      window.dispatchEvent(new Event('vms-session-expired'))
    }
    throw new ApiError(response.status, data)
  }
  if (path.includes('/sessions')) csrf = undefined
  return data
}

export const staff = (path, options) => api('/api/staff' + path, options)
export const visitor = (path, options) => api('/api/visitor' + path, options)
export const post = (body, headers) => ({ method: 'POST', body, headers })

export function params(values) {
  const search = new URLSearchParams(Object.entries(values).filter(([, value]) => value !== '' && value != null))
  return search.size ? '?' + search : ''
}

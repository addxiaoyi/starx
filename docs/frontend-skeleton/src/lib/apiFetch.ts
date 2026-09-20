/**
 * StarMC 统一 fetch 封装。
 * - Cookie 会话（credentials: include）
 * - 对齐 重构/backend/docs/api-contract.md 响应 envelope
 */

export type ApiErrorBody = {
  ok?: false
  success?: false
  message?: string
  code?: string
  error?: string
  details?: Record<string, unknown>
}

export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly details?: Record<string, unknown>

  constructor(status: number, body: ApiErrorBody = {}) {
    super(body.message || body.code || body.error || `HTTP ${status}`)
    this.name = 'ApiError'
    this.code = body.code || body.error || 'unknown_error'
    this.status = status
    this.details = body.details
  }
}

export type PaginationMeta = {
  total: number
  page: number
  pageSize: number
  totalPages?: number
  hasMore?: boolean
  hasPrev?: boolean
}

export type PagedResult<T> = {
  items: T[]
  total: number
  page: number
  pageSize: number
  pagination?: PaginationMeta
}

function resolveApiUrl(path: string): string {
  const normalized = path.startsWith('/') ? path : `/${path}`
  const base = String(import.meta.env.VITE_API_BASE || '').trim().replace(/\/+$/, '')
  return base ? `${base}${normalized}` : normalized
}

export function isApiSuccess(body: unknown): boolean {
  if (!body || typeof body !== 'object') return false
  const record = body as Record<string, unknown>
  return record.ok === true || record.success === true
}

async function parseJsonSafe(res: Response): Promise<unknown> {
  const type = res.headers.get('content-type') || ''
  if (!type.includes('application/json')) return null
  try {
    return await res.json()
  } catch {
    return null
  }
}

/**
 * 通用 JSON API 请求。HTTP 非 2xx 或 body.ok === false 时抛 ApiError。
 */
export async function apiFetch<T = unknown>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body != null && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const res = await fetch(resolveApiUrl(path), {
    ...init,
    headers,
    credentials: 'include',
  })

  const body = await parseJsonSafe(res)

  if (!res.ok) {
    throw new ApiError(res.status, (body as ApiErrorBody) || { message: res.statusText })
  }

  if (body != null && typeof body === 'object' && 'ok' in body && (body as ApiErrorBody).ok === false) {
    throw new ApiError(res.status, body as ApiErrorBody)
  }

  return body as T
}

/** POST JSON 便捷方法 */
export function apiPost<T = unknown>(path: string, payload?: unknown, init: RequestInit = {}): Promise<T> {
  return apiFetch<T>(path, {
    ...init,
    method: 'POST',
    body: payload === undefined ? undefined : JSON.stringify(payload),
  })
}

/** GET 便捷方法 */
export function apiGet<T = unknown>(path: string, init: RequestInit = {}): Promise<T> {
  return apiFetch<T>(path, { ...init, method: 'GET' })
}

/** 从分页响应提取 items（兼容旧 total/page/pageSize） */
export function normalizePagedResponse<T>(body: Record<string, unknown>): PagedResult<T> {
  const items = Array.isArray(body.items) ? (body.items as T[]) : []
  const pagination = body.pagination as PaginationMeta | undefined
  return {
    items,
    total: Number(pagination?.total ?? body.total ?? items.length),
    page: Number(pagination?.page ?? body.page ?? 1),
    pageSize: Number(pagination?.pageSize ?? body.pageSize ?? items.length),
    pagination,
  }
}

/** 开发兜底：SuperTokens 未就绪时 Authorization: Bearer dev:<username> */
export function withDevBearer(username: string, init: RequestInit = {}): RequestInit {
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer dev:${username}`)
  return { ...init, headers }
}

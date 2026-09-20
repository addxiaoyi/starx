import type { ProxyOptions } from 'vite'

/** 避免 `/api-info` 等前端路由被误代理 */
export function bypassNonApiPaths(
  req: { url?: string },
  _res: unknown,
  _options: unknown
): string | undefined {
  const pathname = req.url?.split('?')[0] ?? ''
  if (pathname.startsWith('/api') && pathname !== '/api' && !pathname.startsWith('/api/')) {
    return '/index.html'
  }
}

/**
 * StarMC 开发/预览代理。目标由 VITE_LOCAL_API_TARGET 覆盖（默认 8787）。
 */
export function createStarMcDevProxy(env: Record<string, string>): Record<string, ProxyOptions> {
  const apiTarget = (env.VITE_LOCAL_API_TARGET || 'http://127.0.0.1:8787').replace(/\/+$/, '')
  const apiProxy: ProxyOptions = {
    target: apiTarget,
    changeOrigin: true,
    bypass: bypassNonApiPaths,
  }

  return {
    '/api': apiProxy,
    '/auth': { target: apiTarget, changeOrigin: true },
    '/uploads': { target: apiTarget, changeOrigin: true },
    '/healthz': { target: apiTarget, changeOrigin: true },
  }
}

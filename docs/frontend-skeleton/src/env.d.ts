/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SITE_NAME: string
  readonly VITE_PUBLIC_SITE_ORIGIN: string
  readonly VITE_AUTH_API_DOMAIN: string
  readonly VITE_AUTH_WEBSITE_DOMAIN: string
  readonly VITE_AUTH_API_BASE_PATH: string
  readonly VITE_LOCAL_API_TARGET?: string
  /** 生产跨域部署时设置；开发留空，走 Vite 同源代理 */
  readonly VITE_API_BASE?: string
  readonly VITE_TELEMETRY_ENABLED?: string
  readonly VITE_SKIN_LIBRARY_SSE_PATH?: string
  readonly VITE_SKIN_REALTIME_REFRESH_MS?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

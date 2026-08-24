import { apiGet } from './apiFetch'

export type SiteFeatures = {
  mcGamePasswordReset?: boolean
  inviteRequiredForMcLink?: boolean
  requireTotpForMcLink?: boolean
  reviewEntryEnabled?: boolean
  totpAvailable?: boolean
  geoPolicyActive?: boolean
  mcBindingEnabled?: boolean
  mojangVerifyEnabled?: boolean
  telemetryEnabled?: boolean
  mojangSkinSyncEnabled?: boolean
  skinBridgePublicProfile?: boolean
  vlaNotifyOnSkinChange?: boolean
  pluginBridge?: {
    adapter?: string
    circuitOpen?: boolean
    webhookConfigured?: boolean
  }
  reviewStats?: Record<string, unknown>
}

export type BootstrapPayload = {
  ok: boolean
  success: boolean
  contractVersion: string
  site: {
    name: string
    publicSiteOrigin: string | null
    publicApiOrigin: string | null
  }
  features: SiteFeatures
  auth: {
    apiBasePath: string
    emailCodeLogin: boolean
    emailVerificationOptional: boolean
    oauthProviders: string[]
    supertokensConfigured: boolean
    smtpConfigured: boolean
    devAuthFallback?: boolean
  }
  realtime: {
    skinLibrarySse: string
    skinLibraryPoll: string
    recommendedPollMs: number
  }
  endpoints: Record<string, string>
  docs: Record<string, string>
}

export async function fetchBootstrap(): Promise<BootstrapPayload> {
  return apiGet<BootstrapPayload>('/api/public/bootstrap')
}

export async function fetchCurrentUser<T = unknown>(): Promise<T | null> {
  try {
    return await apiGet<T>('/api/user/me')
  } catch (err) {
    if (err && typeof err === 'object' && 'status' in err && (err as { status: number }).status === 401) {
      return null
    }
    throw err
  }
}

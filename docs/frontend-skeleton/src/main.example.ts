/**
 * 最小启动示例（复制到你的 main.ts / app 入口后按需改写）。
 */
import { fetchBootstrap, fetchCurrentUser } from './lib/bootstrap'

async function boot() {
  const bootstrap = await fetchBootstrap()
  console.info('[starmc] site', bootstrap.site.name, bootstrap.contractVersion)

  const user = await fetchCurrentUser<{ id?: string; username?: string }>()
  console.info('[starmc] session', user ? `logged in as ${user.username ?? user.id}` : 'guest')

  // 示例：按开关隐藏审核入口
  if (!bootstrap.features.reviewEntryEnabled) {
    document.querySelector('[data-route="review"]')?.remove()
  }
}

boot().catch((err) => {
  console.error('[starmc] boot failed', err)
})

import { defineConfig, loadEnv } from 'vite'

import { createStarMcDevProxy } from './vite.proxy'

// 若使用 Vue/React 插件，在此 import 并加入 plugins 数组即可。
// import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    // plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: Number(env.VITE_DEV_PORT || 5173),
      strictPort: false,
      proxy: createStarMcDevProxy(env),
    },
    preview: {
      host: '127.0.0.1',
      port: Number(env.VITE_PREVIEW_PORT || 4173),
      proxy: createStarMcDevProxy(env),
    },
  }
})

import { defineConfig, loadEnv } from 'vite'
import { fileURLToPath, URL } from 'node:url'
import { wrapperEnv } from './build/getEnv'
import { createProxy } from './build/proxy'
import { createVitePlugins } from './build/plugins'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const root = process.cwd()
  const env = loadEnv(mode, root)
  const viteEnv = wrapperEnv(env)

  return {
    plugins: createVitePlugins(viteEnv),
    server: {
      port: 8090,
      host: true,
      // 代理配置
      proxy: {
        ...createProxy(viteEnv.VITE_PROXY),
        // AI 助手（/ai/**）必须走同源代理：
        // 游客会话身份存在 Cookie 里，而浏览器到 localhost:8080 是跨端口请求，
        // Chrome 对跨端口 SameSite=Lax Cookie 的收发不稳定（时带时不带），
        // 会导致每一轮对话都变成新会话——模型"没有记忆"。
        // 同源之后 Cookie 是第一方的，必然随请求发送，对话记忆才连续。
        // 注意不能并进 createProxy：它会把前缀剥掉（/ai/... → /...），路径就错了。
        '/ai': {
          target: 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    base: viteEnv.VITE_PUBLIC_PATH,
    build: {
      // outDir: 'xxxxx', // 设置输出目录
      rollupOptions: {
        // 静态资源分类打包
        output: {
          chunkFileNames: 'static/js/[name]-[hash].js',
          entryFileNames: 'static/js/[name]-[hash].js',
          assetFileNames: 'static/[ext]/[name]-[hash].[ext]',
          // 静态资源分拆打包
          manualChunks(id) {
            if (id.includes('node_modules')) {
              if (id.toString().indexOf('.pnpm/') !== -1) {
                return id.toString().split('.pnpm/')[1].split('/')[0].toString()
              } else if (id.toString().indexOf('node_modules/') !== -1) {
                return id
                  .toString()
                  .split('node_modules/')[1]
                  .split('/')[0]
                  .toString()
              }
            }
          },
        },
      },
    },
  }
})

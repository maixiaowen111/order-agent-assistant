import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理：浏览器只跟 5173 说话，后端跨端口请求全在这里收敛（两个后端都无 CORS）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 业务执行层 order-system :8080
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      // 商品图片静态资源（order-system 的 addResourceHandlers 映射）
      '/uploads': { target: 'http://localhost:8080', changeOrigin: true },
      // AI 决策层 order-agent-assistant :8081
      '/query': { target: 'http://localhost:8081', changeOrigin: true },
      '/approve': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
})

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 开发期把 /api 转给本机的 Spring Boot，前端代码里直接写 /api/xxx 即可，
    // 不用区分环境和跨域。打包部署时前后端同源，这个代理用不上。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})

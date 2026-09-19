import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时：Vite 起在 5173，/api 代理到后端的 8080（省掉跨域配置）
// 构建时：产物直接写进 Spring Boot 的静态资源目录，这样 `java -jar` 单进程就能把整个产品跑起来
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } }
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  }
})

import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

const CONTROL = 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [vue()],
  base: '/ui/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    outDir: '../src/main/resources/static/ui',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (id.includes('node_modules/@codemirror') || id.includes('node_modules/codemirror') || id.includes('node_modules/lezer')) {
            return 'codemirror';
          }
          if (id.includes('node_modules/element-plus')) return 'element-plus';
          if (id.includes('node_modules/chart.js') || id.includes('node_modules/vue-chartjs')) return 'chart';
          if (
            id.includes('node_modules/vue/') ||
            id.includes('node_modules/vue-router') ||
            id.includes('node_modules/pinia') ||
            id.includes('node_modules/vue-i18n') ||
            id.includes('node_modules/@vue')
          ) {
            return 'vendor';
          }
          return undefined;
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: CONTROL, changeOrigin: true, xfwd: true }
    }
  }
});

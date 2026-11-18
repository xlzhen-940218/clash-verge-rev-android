import path from 'node:path'

import legacy from '@vitejs/plugin-legacy'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import svgr from 'vite-plugin-svgr'

const shims = path.resolve('./src/mobile/shims')

// 将 @tauri-apps/api/* 与 @tauri-apps/plugin-* 重定向到移动端 stub
const tauriAliases = {
  '@tauri-apps/api/core': path.join(shims, 'core.ts'),
  '@tauri-apps/api/app': path.join(shims, 'app.ts'),
  '@tauri-apps/api/window': path.join(shims, 'window.ts'),
  '@tauri-apps/api/webviewWindow': path.join(shims, 'webviewWindow.ts'),
  '@tauri-apps/api/event': path.join(shims, 'event.ts'),
  '@tauri-apps/api/path': path.join(shims, 'path.ts'),
  '@tauri-apps/plugin-dialog': path.join(shims, 'plugin-dialog.ts'),
  '@tauri-apps/plugin-fs': path.join(shims, 'plugin-fs.ts'),
  '@tauri-apps/plugin-clipboard-manager': path.join(shims, 'plugin-clipboard-manager.ts'),
  '@tauri-apps/plugin-updater': path.join(shims, 'plugin-updater.ts'),
  '@tauri-apps/plugin-http': path.join(shims, 'plugin-http.ts'),
  '@tauri-apps/plugin-opener': path.join(shims, 'plugin-opener.ts'),
  'tauri-plugin-mihomo-api': path.join(shims, 'tauri-plugin-mihomo-api.ts'),
}

export default defineConfig({
  root: 'src',
  base: './',
  plugins: [
    svgr(),
    react(),
    legacy({
      modernTargets: ['edge>=109', 'safari>=14'],
      renderLegacyChunks: false,
      modernPolyfills: true,
    }),
  ],
  build: {
    // 输出到 Android assets 目录，由 Gradle 打包进 APK
    outDir: '../mobile/android/app/src/main/assets/web',
    emptyOutDir: true,
    chunkSizeWarningLimit: 4000,
  },
  resolve: {
    alias: {
      '@': path.resolve('./src'),
      '@root': path.resolve('.'),
      'monaco-editor/esm/vs/editor/editor.worker.js':
        'monaco-editor/editor/editor.worker',
      ...tauriAliases,
    },
  },
  define: {
    OS_PLATFORM: '"android"',
  },
})

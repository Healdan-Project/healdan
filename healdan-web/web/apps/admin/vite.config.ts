// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import react from '@vitejs/plugin-react'
import { loadEnv, type ProxyOptions } from 'vite'
import { defineConfig } from 'vitest/config'

// `npm run dev` (in apps/admin) serves the app at /admin/ and sends the stack's own addresses to the stack named by
// HEALDAN_DEV_TARGET, a running Healdan (http://127.0.0.1:8080 unless set), as the User app's dev server does: the
// web-session route refuses a request whose Origin isn't the server's own, so the proxy rewrites it, and signing in at
// the stack's address signs in the dev server on the same host name. /app is the User app, which Open Healdan opens.
export default defineConfig(({ mode }) => {
  const target = loadEnv(mode, '.', 'HEALDAN_').HEALDAN_DEV_TARGET || 'http://127.0.0.1:8080'
  const toStack: ProxyOptions = { target, changeOrigin: true, headers: { Origin: target } }
  return {
    base: '/admin/',
    // pressable.css, which both apps' pages link, is @healdan/ui's
    publicDir: '../../packages/ui/public',
    plugins: [react()],
    server: {
      proxy: { '/api': toStack, '/login.jsp': toStack, '/j_spring_security_check': toStack, '/app': toStack },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      // As the User app's: React Aria is a chunk of its own, at about 508 kB with the calendar
      chunkSizeWarningLimit: 600,
      // The libraries are chunks of their own, which a browser keeps across the app's updates, React Aria apart as the
      // biggest. No PDF.js: the Admin app previews no document, and its page's policy allows no worker.
      rolldownOptions: {
        output: {
          codeSplitting: {
            groups: [
              { name: 'react-aria', test: /node_modules[\\/](react-aria|react-aria-components|react-stately|@react-aria|@react-stately|@react-types|@internationalized)[\\/]/ },
              { name: 'vendor', test: /node_modules[\\/]/ },
            ],
          },
        },
      },
    },
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.{ts,tsx}'],
      globals: false,
      setupFiles: ['src/test/setup.ts'],
      css: { include: [/\.css/], modules: { classNameStrategy: 'non-scoped' } },
    },
  }
})

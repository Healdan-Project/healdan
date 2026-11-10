// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import react from '@vitejs/plugin-react'
import { loadEnv, type ProxyOptions } from 'vite'
import { defineConfig } from 'vitest/config'

// `npm run dev` (in apps/user) serves the app at /app/ and sends the stack's own addresses to the stack named by
// HEALDAN_DEV_TARGET, a running Healdan (http://127.0.0.1:8080 unless set). The web-session route refuses a request whose
// Origin isn't the server's own, so the proxy rewrites it. Cookies don't depend on the port, so signing in at the
// stack's address signs the dev server in too.
export default defineConfig(({ mode }) => {
  const target = loadEnv(mode, '.', 'HEALDAN_').HEALDAN_DEV_TARGET || 'http://127.0.0.1:8080'
  const toStack: ProxyOptions = { target, changeOrigin: true, headers: { Origin: target } }
  return {
    base: '/app/',
    plugins: [react()],
    // errors.test.ts reads the contract (healdan-api's openapi.yaml), which is outside this app.
    server: {
      fs: { allow: ['../../../..'] },
      proxy: { '/api': toStack, '/login.jsp': toStack, '/j_spring_security_check': toStack },
    },
    build: { outDir: 'dist', sourcemap: false },
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.{ts,tsx}'],
      globals: false,
      setupFiles: ['src/test/setup.ts'],
      css: { include: [/\.css/], modules: { classNameStrategy: 'non-scoped' } },
    },
  }
})

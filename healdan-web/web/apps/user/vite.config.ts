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
    build: {
      outDir: 'dist',
      sourcemap: false,
      // The app loads once (spec section 5) and React Aria is a chunk of its own on purpose (below); at about 508 kB with
      // the calendar it passes Vite's 500 kB default, so the limit is set just over it.
      chunkSizeWarningLimit: 600,
      // The libraries change less often than the app, so they're chunks of their own, which a browser keeps across the
      // app's updates (AppServlet serves every hashed file as immutable). React Aria is the biggest, so it's apart.
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

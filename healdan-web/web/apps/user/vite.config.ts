// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  base: '/app/',
  plugins: [react()],
  // errors.test.ts reads the contract (healdan-api's openapi.yaml), which is outside this app.
  server: { fs: { allow: ['../../../..'] } },
  build: { outDir: 'dist', sourcemap: false },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    globals: false,
    setupFiles: ['src/test/setup.ts'],
    css: { include: [/\.css/], modules: { classNameStrategy: 'non-scoped' } },
  },
})

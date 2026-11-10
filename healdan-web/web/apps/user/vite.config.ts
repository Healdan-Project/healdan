// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  base: '/app/',
  plugins: [react()],
  build: { outDir: 'dist', sourcemap: false },
  test: { environment: 'jsdom', include: ['src/**/*.test.{ts,tsx}'], globals: false },
})

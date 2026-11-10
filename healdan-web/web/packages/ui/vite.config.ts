// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// CSS Modules work in Vitest as in Vite (readable class names). css.include lets tokens.test.ts import tokens.css as text.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    globals: false,
    setupFiles: ['src/test/setup.ts'],
    css: { include: [/tokens\.css/], modules: { classNameStrategy: 'non-scoped' } },
  },
})

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// The package's own tests. What it draws is tested in each app, inside that app's frame.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    globals: false,
    css: { include: [/\.css/], modules: { classNameStrategy: 'non-scoped' } },
  },
})

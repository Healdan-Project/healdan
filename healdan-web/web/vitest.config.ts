// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { defineConfig } from 'vitest/config'

// Each app's and package's own vite.config.ts defines its project (Vitest 5 has no vitest.workspace.ts).
export default defineConfig({
  test: { projects: ['apps/*', 'packages/api'] },
})

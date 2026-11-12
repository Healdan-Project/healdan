// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { defineConfig, devices } from '@playwright/test'

// The User app's browser tests run against a Docker smoke stack (docker/tests/test-web.sh), in the Playwright image
// whose version is this package's @playwright/test. BASE is the stack's address.
export default defineConfig({
  testDir: 'e2e',
  // A fresh stack's first page load is slow, and the old UI's sign-in page is a GWT app.
  timeout: 90_000,
  expect: { timeout: 30_000 },
  retries: 0,
  // A stack that came up healthy but broken would make every spec wait out its sign-in, an hour in all, and the job's
  // timeout would then end the run before docker/smoke-test.sh says why: ten failures are enough to stop for
  maxFailures: 10,
  fullyParallel: false,
  workers: 1,
  outputDir: 'e2e-results',
  use: {
    baseURL: process.env.BASE ?? 'http://127.0.0.1:18080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
})

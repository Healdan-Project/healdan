// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// The few Node functions the tests that read files use, typed here because the app has no @types/node.
declare module 'node:module' {
  export function createRequire(url: string): { resolve(id: string): string }
}

declare module 'node:fs' {
  export function readFileSync(path: string, encoding: 'utf8'): string
  export function existsSync(path: string): boolean
}

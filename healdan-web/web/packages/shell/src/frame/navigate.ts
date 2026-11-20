// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Leaving the app for a page of the server's own (sign-in). Apart so a test can stand in for it.
export function goTo(url: string): void {
  window.location.assign(url)
}

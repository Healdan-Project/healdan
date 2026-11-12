// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/**
 * A matter as the API names it, `1001.1 – Charter Dispute`: its number, and its name when the string has one. (The API
 * writes an en dash; a hyphen is read too.)
 */
export function matterParts(display: string): { number: string; name?: string } {
  const parts = /^(\S+)\s+[–-]\s+(.+)$/.exec(display)
  return parts ? { number: parts[1]!, name: parts[2]! } : { number: /^\S+/.exec(display)?.[0] ?? display }
}

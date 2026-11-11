// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Dates come from the server as ISO instants and show in the browser's locale and time zone.
function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

// A value that isn't a date shows as nothing rather than breaking the list around it.
// Today's dates show the time ("9:42 AM"), any other day the date ("Sep 29, 2026").
export function formatDate(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  if (sameDay(date, new Date())) {
    return new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' }).format(date)
  }
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).format(date)
}

/** A day with no time, as 2026-11-02, shown in the person's style ("Nov 2, 2026") whichever day it is today and in whatever time zone. */
export function formatDay(day: string): string {
  const parts = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day)
  if (!parts) return ''
  // Built from its parts in the local zone, so the zone can't move it to the day before or after
  const date = new Date(Number(parts[1]), Number(parts[2]) - 1, Number(parts[3]))
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric' }).format(date)
}

export function formatDateTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

/** Healdan's numbering: 2.0 is version 2, and 2.1 a snapshot on its timeline. */
export function formatVersion(version: string): string {
  return version.endsWith('.0') ? version.slice(0, -2) : version
}

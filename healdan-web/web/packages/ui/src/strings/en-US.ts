// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// US English. Labels are in Title Case. {matter} and {matters} are the firm's own words (setLabels); other {name}s
// are filled from the variables t() is given.
export const enUS = {
  'common.cancel': 'Cancel',
  'common.close': 'Close',
  'common.ok': 'OK',
  'common.save': 'Save',
  'common.menu': 'Menu',
  'toast.region': 'Notifications',
  'toast.dismiss': 'Dismiss',
  'filters.count': '{n} Filters',
  'favorites.title': 'Favorites',
  'settings.color': 'Color',
  'matters.title': '{matters}',
  'matters.new': 'New {matter}',
  'matters.search': 'Search {matters}',
} as const

export type StringKey = keyof typeof enUS

// Each plural has the forms Intl.PluralRules('en-US') picks between.
export const enUSPlurals = {
  documents: { one: '{n} document', other: '{n} documents' },
  folders: { one: '{n} folder', other: '{n} folders' },
  matters: { one: '{n} {matter}', other: '{n} {matters}' },
} as const

export type PluralKey = keyof typeof enUSPlurals

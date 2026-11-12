// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { t } from '@healdan/ui'
import { NotFound } from './NotFound'
import { NotYet } from './NotYet'
import { RecentDocuments } from './RecentDocuments'

export const recentKinds = ['documents', 'emails', 'folders', 'clients'] as const
export type RecentKind = (typeof recentKinds)[number]

export function isRecentKind(value: unknown): value is RecentKind {
  return recentKinds.some((kind) => kind === value)
}

// /recent/<kind>: Documents is the start page's list; the others come with their own tasks.
export function RecentKindView({ kind }: { kind: string }) {
  if (!isRecentKind(kind)) return <NotFound />
  if (kind === 'documents') return <RecentDocuments />
  return <NotYet title={t(`recent.heading.${kind}`)} />
}

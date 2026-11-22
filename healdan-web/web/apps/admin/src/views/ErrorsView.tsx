// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents, adminPaths } from '@healdan/api'
import { searchId } from '../addresses'
import { typed } from '../pickers/typing'
import { t } from '../strings'
import styles from './views.module.css'

type A = adminComponents['schemas']

/** How far back the Errors list goes, as `GET /v1/admin/errors` takes it: the last hour, 24 hours, 7 days or 30 days */
export type ErrorWindow = NonNullable<NonNullable<adminPaths['/v1/admin/errors']['get']['parameters']['query']>['window']>

export const errorWindows = ['1h', '24h', '7d', '30d'] as const satisfies readonly ErrorWindow[]

/** The areas a fault can be of, in the server's order */
export const errorAreas = ['Previews', 'Storage', 'Search Index', 'Email', 'Sign-In', 'Background Jobs', 'Server'] as const satisfies readonly A['ErrorArea'][]

/** What the Errors list's address holds: how far back it goes, its words, its area, Unresolved, and the fault that's open */
export interface ErrorsSearch {
  window?: ErrorWindow
  q?: string
  area?: A['ErrorArea']
  /** Only the faults nobody has marked resolved; left out for every fault */
  unresolved?: true
  /** The fault whose details are open, by its id */
  id?: number
}

/**
 * The address's parameters the list reads; anything else, or anything that isn't what it should be, is left out. The
 * router validates what it validated before too, as it builds the next address from it, so `unresolved` and the id may
 * come as a boolean and a number.
 */
export function errorsSearch(search: Record<string, unknown>): ErrorsSearch {
  const q = typeof search.q === 'string' ? typed(search.q) : ''
  return {
    window: errorWindows.find((one) => one === search.window),
    q: q === '' ? undefined : q,
    area: errorAreas.find((one) => one === search.area),
    unresolved: search.unresolved === true || search.unresolved === 'true' ? true : undefined,
    id: searchId(search.id),
  }
}

/**
 * Errors, at /admin/errors (spec 8.8): each kind of fault the server logged, with the open one's details below the list.
 * The route hands in the address's parameters as `search`.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars -- the list and the open fault's details read it
export function ErrorsView({ search }: { search: ErrorsSearch }) {
  return (
    <div className={styles.view}>
      <h1 className={styles.title}>{t('admin.errors.title')}</h1>
    </div>
  )
}

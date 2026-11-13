// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { components } from '@healdan/api'
import { Icon, t, type ComboBoxSection } from '@healdan/ui'
import { fileIcon } from '../fileIcon'
import { matterParts } from '../matterDisplay'
import { keys } from '../queries'
import { useApi } from '../services'
import type { SearchScope } from './scopes'

type S = components['schemas']

/** Suggestions start at two characters, wait for the typing to pause, and send at most this many characters. */
export const SUGGEST_MIN = 2
export const SUGGEST_MAX = 100
export const SUGGEST_DELAY_MS = 150

/** What a suggestion's option id says it is: `m11` a matter, `d10392` a document, `search` the search itself. */
export const SEARCH_ID = 'search'

/** The value once it has stayed the same for `delay` milliseconds */
export function useDebounced<T>(value: T, delay: number): T {
  const [settled, setSettled] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return settled
}

/** What the text asks the server to suggest, or nothing: too short, or a # (which goes straight to its document). */
export function suggestable(text: string): string | undefined {
  const q = text.trim().slice(0, SUGGEST_MAX).trim()
  return q.length >= SUGGEST_MIN && !q.startsWith('#') ? q : undefined
}

/**
 * The server's suggestions for what is typed, asked for once the typing pauses. A newer ask cancels the older one in
 * flight (its signal is passed to the request, and TanStack Query aborts it when its key is no longer wanted). The
 * last answer stays until the next, so the list doesn't blink while typing.
 */
export function useSuggestions(text: string): S['SuggestView'] | undefined {
  const api = useApi()
  const wanted = suggestable(text)
  const settled = useDebounced(wanted, SUGGEST_DELAY_MS)
  const query = useQuery({
    queryKey: keys.suggest(settled ?? ''),
    queryFn: ({ signal }) => api.get<S['SuggestView']>('/v1/suggest', { q: settled }, { signal }),
    enabled: settled !== undefined,
    retry: false,
    // A new search of the same words is a fresh look; but a keystroke back and forth needn't ask twice
    staleTime: 10_000,
    placeholderData: (previous) => previous,
  })
  // Nothing to show once the text is too short or a #, whatever the last answer was
  return wanted === undefined ? undefined : query.data
}

/** A matter as the search shows it: `1001.1 – Charter Dispute`, as everywhere in the app. */
export function matterLabel(matter: S['SuggestView']['matters'][number]): string {
  return t('matter.label', { number: `${matter.client.number}.${matter.number}`, name: matter.name })
}

/** The options of the list: matters, then documents, then the search itself, which Enter runs when nothing is picked. */
export function suggestionSections(answer: S['SuggestView'] | undefined, scope: SearchScope, q: string): ComboBoxSection[] {
  const sections: ComboBoxSection[] = []
  if (answer && answer.matters.length > 0) {
    sections.push({
      id: 'matters',
      title: t('search.suggestions.matters'),
      options: answer.matters.map((matter) => ({
        id: `m${matter.id}`,
        textValue: matterLabel(matter),
        icon: <Icon name="briefcase" />,
        label: matterLabel(matter),
        meta: matter.client.name,
      })),
    })
  }
  if (answer && answer.documents.length > 0) {
    sections.push({
      id: 'documents',
      title: t('search.suggestions.documents'),
      options: answer.documents.map((doc) => ({
        id: `d${doc.docNumber}`,
        textValue: doc.name,
        icon: <Icon name={fileIcon(doc.name)} />,
        label: doc.name,
        meta: t('search.documentMeta', { n: doc.docNumber, matter: matterParts(doc.matter.display).number }),
      })),
    })
  }
  const text = t('search.for', { scope: t(`scope.${scope}`), q })
  sections.push({ id: 'run', title: t('search.suggestions.run'), showTitle: false, options: [{ id: SEARCH_ID, textValue: text, icon: <Icon name="search" size={14} />, label: text }] })
  return sections
}

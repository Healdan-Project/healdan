// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import type { adminComponents, components } from '@healdan/api'
import { useApi, type PageOf } from '@healdan/shell'
import { PickField, type PickFieldOption } from '@healdan/ui'
import { useAdminQuery } from '../queries'
import { t } from '../strings'
import { typed, useDebounced } from './typing'

type A = adminComponents['schemas']
type S = components['schemas']

/**
 * Whose accounts a picker offers, which every caller chooses:
 * - `enabled`: the people who can sign in, so can own a matter, for a field that makes someone an owner (New Matter's and
 *   Edit's Owner): `GET /v1/users`, which answers 20 at most.
 * - `all`: those too who have left (a disabled account), for a filter, as their matters stay theirs (the Matters list's
 *   Owner): the admin find's people (`GET /v1/admin/find`), 5 at most, each who has left saying so beside the username.
 * - `users`: everyone the Users list lists, those who have left too, for a filter whose person may be one of many who
 *   match (the Audit Trail's Person, P5-R23): `GET /v1/admin/users`, by name, PEOPLE_SEARCH_LIMIT at most, each who has
 *   left saying so beside the username.
 */
export type PersonAccounts = 'enabled' | 'all' | 'users'

/** The most people a search of the Users list offers: the first by name */
export const PEOPLE_SEARCH_LIMIT = 20

export interface PersonPickerProps {
  label: string
  accounts: PersonAccounts
  required?: boolean
  error?: string
  /** The person the field holds, or null */
  value: A['PersonBrief'] | null
  onChange: (person: A['PersonBrief'] | null) => void
}

/** A person found, with whether their account can sign in */
export type Person = A['PersonBrief'] & { enabled: boolean }

/**
 * The people found for the words (a picker's settled, trimmed text; nothing is asked for none), from the source
 * `accounts` names; the others are never asked. Workspace Editor's Add Person or Group asks it too.
 */
export function usePeople(q: string, accounts: PersonAccounts): Person[] {
  const api = useApi()
  const canOwn = useQuery({
    // The User app's key for the same question
    queryKey: ['users', q],
    queryFn: ({ signal }) => api.get<PageOf<S['UserHit']>>('/v1/users', { q }, { signal }),
    enabled: accounts === 'enabled' && q !== '',
    staleTime: 30_000,
    placeholderData: (previous) => previous,
  })
  // The top bar's find asks the same, so a question asked there and here is asked once
  const everyone = useAdminQuery('/v1/admin/find', { query: { q }, enabled: accounts === 'all' && q !== '', keepPrevious: true, staleTime: 30_000 })
  // Under the Users list's key, so a change to someone asks it again
  const listed = useAdminQuery('/v1/admin/users', { query: { q, limit: PEOPLE_SEARCH_LIMIT }, enabled: accounts === 'users' && q !== '', keepPrevious: true, staleTime: 30_000 })
  if (accounts === 'enabled') return (canOwn.data?.items ?? []).map((person) => ({ ...person, enabled: true }))
  if (accounts === 'users') return (listed.data?.items ?? []).map((row: A['UserRow']) => ({ id: row.id, username: row.username, name: row.name, enabled: row.status !== 'disabled' }))
  return everyone.data?.people ?? []
}

function optionOf(person: Person): PickFieldOption {
  return { id: String(person.id), label: person.name, icon: 'user', meta: person.enabled ? person.username : t('admin.person.disabled', { username: person.username }) }
}

/**
 * A person, found as it is typed (spec 7.2: anything that can grow long is a search): by username, first or last name (one
 * character is enough), from the accounts the caller chooses (`accounts`), each offered by name with the username beside
 * it.
 */
export function PersonPicker({ label, accounts, required, error, value, onChange }: PersonPickerProps) {
  const [text, setText] = useState('')
  const q = typed(text)
  const settled = useDebounced(q)
  const found = usePeople(settled, accounts)
  const people = q === '' ? [] : found
  // What the field holds shows by name only, whatever its account
  const picked: PickFieldOption | null = value && { id: String(value.id), label: value.name, icon: 'user', meta: value.username }
  return (
    <PickField
      label={label}
      required={required}
      error={error}
      value={picked}
      options={people.map(optionOf)}
      // The box shows the name of who is picked, which is no search
      onTextChange={(next) => setText(next === picked?.label ? '' : next)}
      // PickField hands on only an option it offered, other than the one it holds
      onChange={(next) => {
        if (next === null) return onChange(null)
        const person = people.find((one) => String(one.id) === next.id)
        if (person) onChange({ id: person.id, username: person.username, name: person.name })
      }}
    />
  )
}

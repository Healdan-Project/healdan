// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import type { components } from '@healdan/api'
import { PickField } from '@healdan/ui'
import type { PageOf } from '../pages'
import { keys } from '../queries'
import { useDebounced } from '../search/Suggestions'
import { useApi } from '../services'

type S = components['schemas']

/** People are looked for once the typing pauses, for at most this many characters */
export const PEOPLE_DELAY_MS = 150
const PEOPLE_MAX = 100

export interface UserFieldProps {
  label: string
  required?: boolean
  error?: string
  /** The person the field holds, or null */
  value: S['UserRef'] | null
  onChange: (value: S['UserRef'] | null) => void
}

/**
 * A property that holds a person, such as Author or Typist (R92): a picker that asks `GET /v1/users` for the people
 * whose names hold what is typed (one character is enough; the server answers 20 at most), and offers the field's own
 * person first.
 */
export function UserField({ label, required, error, value, onChange }: UserFieldProps) {
  const api = useApi()
  const [text, setText] = useState('')
  const q = text.trim().slice(0, PEOPLE_MAX).trim()
  const settled = useDebounced(q, PEOPLE_DELAY_MS)
  const people = useQuery({
    queryKey: keys.users(settled),
    queryFn: ({ signal }) => api.get<PageOf<S['UserHit']>>('/v1/users', { q: settled }, { signal }),
    enabled: settled !== '',
    retry: false,
    staleTime: 30_000,
    placeholderData: (previous) => previous,
  })
  const options = q === '' ? [] : (people.data?.items ?? []).map((person) => ({ id: String(person.id), label: person.name }))
  const picked = value ? { id: String(value.id), label: value.name ?? '' } : null
  return (
    <PickField
      label={label}
      required={required}
      error={error}
      value={picked}
      options={options}
      onTextChange={(next) => setText(next === picked?.label ? '' : next)}
      onChange={(next) => onChange(next ? { id: Number(next.id), name: next.label } : null)}
    />
  )
}

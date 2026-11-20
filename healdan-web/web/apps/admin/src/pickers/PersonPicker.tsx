// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import type { adminComponents, components } from '@healdan/api'
import { useApi, type PageOf } from '@healdan/shell'
import { PickField, type PickFieldOption } from '@healdan/ui'
import { typed, useDebounced } from './typing'

type A = adminComponents['schemas']
type S = components['schemas']

export interface PersonPickerProps {
  label: string
  required?: boolean
  error?: string
  /** The person the field holds, or null */
  value: A['PersonBrief'] | null
  onChange: (person: A['PersonBrief'] | null) => void
}

function optionOf(person: A['PersonBrief']): PickFieldOption {
  return { id: String(person.id), label: person.name, icon: 'user', meta: person.username }
}

/**
 * A person, found as it is typed (spec 7.2: anything that can grow long is a search), such as a matter's owner: the
 * people `GET /v1/users` finds for the words, by username, first or last name (one character is enough; the server
 * answers 20 at most, active accounts only), each offered by name with the username beside it.
 */
export function PersonPicker({ label, required, error, value, onChange }: PersonPickerProps) {
  const api = useApi()
  const [text, setText] = useState('')
  const q = typed(text)
  const settled = useDebounced(q)
  const found = useQuery({
    // The User app's key for the same question
    queryKey: ['users', settled],
    queryFn: ({ signal }) => api.get<PageOf<S['UserHit']>>('/v1/users', { q: settled }, { signal }),
    enabled: settled !== '',
    staleTime: 30_000,
    placeholderData: (previous) => previous,
  })
  const people = q === '' ? [] : (found.data?.items ?? [])
  const picked = value && optionOf(value)
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

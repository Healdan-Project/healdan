// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { PickField, type PickFieldOption } from '@healdan/ui'
import { useAdminQuery } from '../queries'
import { typed, useDebounced } from './typing'

type A = adminComponents['schemas']

/** The most groups a search offers: the first by name */
const GROUP_SEARCH_LIMIT = 20

export interface GroupPickerProps {
  label: string
  /** The group the field holds, or null */
  value: A['GroupBrief'] | null
  onChange: (group: A['GroupBrief'] | null) => void
}

function optionOf(group: A['GroupBrief']): PickFieldOption {
  return { id: String(group.id), label: group.name, icon: 'usersGroup' }
}

/**
 * A group, found as its name is typed (spec 7.2: anything that can grow long is a search), from the groups
 * `GET /v1/admin/groups` lists, asked once the typing pauses: the Users list's Group filter.
 */
export function GroupPicker({ label, value, onChange }: GroupPickerProps) {
  const [text, setText] = useState('')
  const picked = value && optionOf(value)
  const q = typed(text)
  const settled = useDebounced(q)
  const found = useAdminQuery('/v1/admin/groups', {
    query: { q: settled, limit: GROUP_SEARCH_LIMIT },
    enabled: settled !== '',
    keepPrevious: true,
    // A keystroke back and forth needn't ask twice
    staleTime: 30_000,
  })
  const groups: A['Group'][] = q === '' ? [] : (found.data?.items ?? [])
  const byId = new Map(groups.map((group) => [String(group.id), group]))
  return (
    <PickField
      label={label}
      value={picked}
      options={groups.map(optionOf)}
      // The box shows the name of what is picked, which is no search
      onTextChange={(next) => setText(next === picked?.label ? '' : next)}
      // PickField hands on only an option it offered, other than the one it holds
      onChange={(next) => {
        if (next === null) return onChange(null)
        const group = byId.get(next.id)
        if (group) onChange({ id: group.id, name: group.name })
      }}
    />
  )
}

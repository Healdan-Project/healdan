// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { PickField, type PickFieldOption } from '@healdan/ui'
import { useAdminQuery } from '../queries'
import { t } from '../strings'
import { typed, useDebounced } from './typing'

type A = adminComponents['schemas']

/** The most matters a search offers: the first by number */
export const MATTER_SEARCH_LIMIT = 20

export interface MatterPickerProps {
  /** The firm's word for a matter, unless said otherwise */
  label?: string
  /** The matter the field holds, or null */
  value: A['MatterBrief'] | null
  onChange: (matter: A['MatterBrief'] | null) => void
}

/** A matter as a picker names it: its number, then its name */
export function matterLabel(matter: A['MatterBrief']): string {
  return t('matter.label', { number: matter.number, name: matter.name })
}

/**
 * The matters for what is typed: those whose number, as `1001.1`, starts with it, or whose name or client's name holds it,
 * in any case (`GET /v1/admin/matters`), asked once the typing pauses, under the Matters list's key. One character is
 * enough, as a number can be one digit. The last answer stays while the next is asked, so the list doesn't blink.
 */
function useMatterSearch(text: string): A['MatterRow'][] {
  const q = typed(text)
  const settled = useDebounced(q)
  const found = useAdminQuery('/v1/admin/matters', {
    query: { q: settled, limit: MATTER_SEARCH_LIMIT },
    enabled: settled !== '',
    keepPrevious: true,
    // A keystroke back and forth needn't ask twice
    staleTime: 30_000,
  })
  return q === '' ? [] : (found.data?.items ?? [])
}

/**
 * A matter, found as it is typed (spec 7.2: anything that can grow long is a search): by its number, its name or its
 * client's name, each offered as `1001.1 – Charter Dispute` with its client's name beside it.
 */
export function MatterPicker({ label = t('admin.matterPicker.label'), value, onChange }: MatterPickerProps) {
  const [text, setText] = useState('')
  const picked: PickFieldOption | null = value && { id: String(value.id), label: matterLabel(value), icon: 'briefcase' }
  const matters = useMatterSearch(text)
  const options = matters.map((matter): PickFieldOption => ({ id: String(matter.id), label: matterLabel(matter), icon: 'briefcase', meta: matter.client.name }))
  return (
    <PickField
      label={label}
      value={picked}
      options={options}
      // The box shows the label of what is picked, which is no search
      onTextChange={(next) => setText(next === picked?.label ? '' : next)}
      // PickField hands on only an option it offered, other than the one it holds
      onChange={(next) => {
        if (next === null) return onChange(null)
        const matter = matters.find((one) => String(one.id) === next.id)
        if (matter) onChange({ id: matter.id, number: matter.number, name: matter.name })
      }}
    />
  )
}

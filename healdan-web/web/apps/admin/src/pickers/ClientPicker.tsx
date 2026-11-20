// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { PickField, type PickFieldOption } from '@healdan/ui'
import { t } from '../strings'
import { useClientSearch } from './useClientSearch'

type A = adminComponents['schemas']

export interface ClientPickerProps {
  /** Client, unless said otherwise */
  label?: string
  required?: boolean
  error?: string
  /** The client the field holds, or null */
  value: A['ClientBrief'] | null
  onChange: (client: A['ClientBrief'] | null) => void
}

/** A client as a picker names it: its number, then its name */
export function clientLabel(client: A['ClientBrief']): string {
  return t('admin.client.label', { number: client.number, name: client.name })
}

function optionOf(client: A['ClientBrief']): PickFieldOption {
  return { id: String(client.id), label: clientLabel(client), icon: 'building' }
}

/**
 * A client, found as it is typed (spec 7.2: anything that can grow long is a search): by its number or its name, each
 * offered as `1001 – Saltmarsh Shipping Ltd`, through useClientSearch.
 */
export function ClientPicker({ label = t('admin.client.field'), required, error, value, onChange }: ClientPickerProps) {
  const [text, setText] = useState('')
  const picked = value && optionOf(value)
  const { clients } = useClientSearch(text)
  const byId = new Map(clients.map((client) => [String(client.id), client]))
  return (
    <PickField
      label={label}
      required={required}
      error={error}
      value={picked}
      options={clients.map(optionOf)}
      // The box shows the label of what is picked, which is no search
      onTextChange={(next) => setText(next === picked?.label ? '' : next)}
      // PickField hands on only an option it offered, other than the one it holds
      onChange={(next) => {
        if (next === null) return onChange(null)
        const client = byId.get(next.id)
        if (client) onChange({ id: client.id, number: client.number, name: client.name })
      }}
    />
  )
}

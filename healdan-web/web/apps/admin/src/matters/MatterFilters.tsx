// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'
import { Button, SelectField, TextField } from '@healdan/ui'
import { ClientPicker } from '../pickers/ClientPicker'
import { PersonPicker } from '../pickers/PersonPicker'
import { useStores } from '../queries'
import { t } from '../strings'
import styles from './Matters.module.css'

type A = adminComponents['schemas']

export interface MatterFiltersProps {
  /** The words typed, as typed */
  text: string
  onTextChange: (text: string) => void
  client: A['ClientBrief'] | null
  onClientChange: (client: A['ClientBrief'] | null) => void
  /** The Store's id, or undefined for every Store */
  storeId: number | undefined
  onStoreChange: (storeId: number | undefined) => void
  owner: A['PersonBrief'] | null
  onOwnerChange: (owner: A['PersonBrief'] | null) => void
  /** Takes every filter off; there's no Clear Filters without it */
  onClear?: () => void
}

/**
 * The filters above the Matters list (spec 8.3, 7.2): the number or name, typed; Client and Owner, searches, as either
 * can grow long; and Store, a plain menu of every Store, as there are few.
 */
export function MatterFilters({ text, onTextChange, client, onClientChange, storeId, onStoreChange, owner, onOwnerChange, onClear }: MatterFiltersProps) {
  const stores = useStores().data ?? []
  return (
    <div role="group" aria-label={t('admin.matters.filters')} className={styles.filters}>
      <div className={`${styles.filter} ${styles.words}`}>
        <TextField label={t('admin.matters.text')} value={text} onChange={onTextChange} />
      </div>
      <div className={styles.filter}>
        <ClientPicker value={client} onChange={onClientChange} />
      </div>
      <div className={styles.filter}>
        <SelectField
          label={t('admin.matters.store')}
          value={storeId === undefined ? '' : String(storeId)}
          onChange={(value) => onStoreChange(value === '' ? undefined : Number(value))}
          options={stores.map((store) => ({ value: String(store.id), label: store.name }))}
          empty={t('admin.matters.allStores')}
        />
      </div>
      <div className={styles.filter}>
        <PersonPicker label={t('admin.matters.owner')} value={owner} onChange={onOwnerChange} />
      </div>
      {onClear && <Button onPress={onClear}>{t('admin.matters.clear')}</Button>}
    </div>
  )
}

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { Button, ComboBox, Dialog, Icon, type ComboBoxSection } from '@healdan/ui'
import { usePeople } from '../pickers/PersonPicker'
import { typed, useDebounced } from '../pickers/typing'
import { useAdminQuery } from '../queries'
import { t } from '../strings'
import type { Grant } from './grants'
import { permissionsOf } from './levels'
import styles from '../clients/Clients.module.css'
import workspace from './Workspace.module.css'

type A = adminComponents['schemas']

/** How many of each a search offers */
const FOUND_LIMIT = 20

export interface AddPersonOrGroupProps {
  /** The groups and people the folder's rows hold already, which aren't offered again */
  taken: { groups: ReadonlySet<number>; people: ReadonlySet<number> }
  /** One was picked: a new row, at Read Only */
  onAdd: (grant: Grant) => void
  onClose: () => void
}

/**
 * Add Person or Group (spec 8.4): one search of both, as it is typed, the groups from `GET /v1/admin/groups` and the
 * people who can sign in from `GET /v1/users` (spec 7.2: anything that can grow long is a search). A person's row is
 * their own group, which LogicalDOC keeps for each person; the save names them by their id (P2-R15). The new row starts
 * at Read Only, the narrowest level, as a folder inside narrows its parent.
 */
export function AddPersonOrGroup({ taken, onAdd, onClose }: AddPersonOrGroupProps) {
  const [text, setText] = useState('')
  const q = typed(text)
  const settled = useDebounced(q)
  const groups = useAdminQuery('/v1/admin/groups', { query: { q: settled, limit: FOUND_LIMIT }, enabled: settled !== '', keepPrevious: true, staleTime: 30_000 })
  const people = usePeople(settled, 'enabled')

  const listed: A['Group'][] = groups.data?.items ?? []
  const foundGroups = q === '' ? [] : listed.filter((group) => !taken.groups.has(group.id))
  const foundPeople = q === '' ? [] : people.filter((person) => !taken.people.has(person.id))
  const sections: ComboBoxSection[] = [
    {
      id: 'groups',
      title: t('admin.access.groups'),
      options: foundGroups.map((group) => ({ id: `g${group.id}`, textValue: group.name, icon: <Icon name="usersGroup" />, label: group.name })),
    },
    {
      id: 'people',
      title: t('admin.access.people'),
      options: foundPeople.map((person) => ({ id: `u${person.id}`, textValue: person.name, icon: <Icon name="user" />, label: person.name, meta: person.username })),
    },
  ].filter((section) => section.options.length > 0)

  function pick(id: string, close: () => void) {
    const group = foundGroups.find((one) => `g${one.id}` === id)
    const person = foundPeople.find((one) => `u${one.id}` === id)
    const permissions = permissionsOf('read-only')
    if (group) onAdd({ key: id, groupId: group.id, userId: null, person: false, name: group.name, owner: false, permissions })
    else if (person) onAdd({ key: id, groupId: null, userId: person.id, person: true, name: person.name, owner: false, permissions })
    else return
    close()
  }

  return (
    <Dialog title={t('admin.access.add')} isOpen onOpenChange={(open) => !open && onClose()}>
      {(close) => (
        <div className={styles.form}>
          <ComboBox
            aria-label={t('admin.access.find')}
            placeholder={t('admin.access.findPlaceholder')}
            value={text}
            onValueChange={setText}
            sections={sections}
            inputClassName={workspace.search}
            onSelect={(id) => pick(id, close)}
            // Enter with nothing picked adds nothing: a row is a group or a person found
            onSubmit={() => {}}
          />
          <p className={styles.note}>{t('admin.access.findHint')}</p>
          <div className={styles.buttons}>
            <Button onPress={close}>{t('common.cancel')}</Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}

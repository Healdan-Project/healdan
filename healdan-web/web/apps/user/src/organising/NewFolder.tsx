// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { components } from '@healdan/api'
import { Button, Dialog, Icon, t, TextField, toast } from '@healdan/ui'
import { invalidateDocumentLists } from '../actions/refresh'
import type { Place } from '../filing/dropTargets'
import { fieldErrors, messageFor } from '../frame/errors'
import { useMe } from '../queries'
import { useApi } from '../services'
import { useOrganising } from './OrganisingProvider'
import styles from './organising.module.css'

type S = components['schemas']

/**
 * Where New Folder is offered: inside a matter's folders, and at a matter's top level only for an administrator, as M1
 * allows. Never in a Store or a search folder. The toolbar's button and a folder's right-click menu both ask it.
 */
export function mayMakeFolderIn(place: Place, admin: boolean | undefined): boolean {
  return place.kind === 'folder' || (place.kind === 'matter' && admin === true)
}

/** The toolbar's New Folder, where it's offered (mayMakeFolderIn) */
export function NewFolderButton({ place }: { place: Place }) {
  const me = useMe()
  const organising = useOrganising()
  if (!mayMakeFolderIn(place, me.data?.admin)) return null
  return (
    <Button onPress={() => organising.askNewFolder(place)}>
      <Icon name="folderPlus" size={14} /> {t('newFolder.button')}
    </Button>
  )
}

export interface NewFolderDialogProps {
  /** The folder the new one is made in */
  parent: Place
  /** Closes the dialog, made or not */
  onCancel: () => void
}

/** Asks for a name and makes the folder, through `POST /v1/folders`, which keeps the rules the old UI keeps. */
export function NewFolderDialog({ parent, onCancel }: NewFolderDialogProps) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function create(close: () => void) {
    setBusy(true)
    try {
      const made = await api.send<S['FolderView']>('POST', '/v1/folders', { parentId: parent.id, name: name.trim() })
      toast(t('newFolder.created', { name: made.name }))
      close()
    } catch (failure) {
      setError(fieldErrors(failure).get('name') ?? messageFor(failure))
    } finally {
      setBusy(false)
      // Whether it worked or not, the folder's lists and the tree's are asked again
      void invalidateDocumentLists(queryClient)
    }
  }

  return (
    <Dialog title={t('newFolder.title')} isOpen onOpenChange={(open) => !open && onCancel()}>
      {(close) => (
        <form
          className={styles.picker}
          onSubmit={(event) => {
            event.preventDefault()
            if (name.trim() !== '' && !busy) void create(close)
          }}
        >
          <TextField
            label={t('newFolder.name')}
            autoFocus
            value={name}
            error={error}
            onChange={(value) => {
              setName(value)
              setError(undefined)
            }}
          />
          <div className={styles.actions}>
            <Button type="submit" variant="default" isDisabled={name.trim() === '' || busy}>
              {t('newFolder.create')}
            </Button>
            <Button onPress={close}>{t('common.cancel')}</Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}

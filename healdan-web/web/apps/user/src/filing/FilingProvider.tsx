// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useState, useSyncExternalStore, type DragEvent, type ReactNode } from 'react'
import { apiPath, type components } from '@healdan/api'
import { t, toast } from '@healdan/ui'
import { invalidateDocumentLists } from '../actions/refresh'
import { isEditable } from '../details/FieldsForm'
import { messageFor } from '../frame/errors'
import type { PageOf } from '../pages'
import { keys } from '../queries'
import { useApi } from '../services'
import { carriesFiles, carriesRows, refusalOf, type Place } from './dropTargets'
import { FilingForm } from './FilingForm'
import { FilingTray } from './FilingTray'
import { createUploads, named, type UploadItem, type Uploads } from './uploads'
import { VersionOrNew } from './VersionOrNew'

type S = components['schemas']

export interface Filing {
  /** What the tray shows: every item since the page opened that hasn't been dismissed */
  items: readonly UploadItem[]
  uploads: Uploads
  /**
   * A drop on a folder, or on one document's row of its folder. It files what it carries, or says why it won't, and
   * returns true; false means the drop wasn't the filing's, as when it carries the app's own rows.
   */
  drop: (event: DragEvent, place: Place, row?: S['DocumentView']) => boolean
  /** Files chosen with the Upload button, into a folder */
  pick: (files: File[], place: Place) => void
}

const Context = createContext<Filing | null>(null)

export function useFiling(): Filing {
  const filing = useContext(Context)
  if (!filing) throw new Error('useFiling needs a FilingProvider')
  return filing
}

interface PendingForm {
  files: File[]
  place: Place
  fields: S['ProfileField'][]
}

interface PendingVersion {
  file: File
  document: S['DocumentView']
}

/** A required property with nothing in it: the form has to ask before anything is filed */
const lacksRequired = (fields: S['ProfileField'][]) => fields.some((field) => isEditable(field) && field.required && (field.value === null || field.value === undefined || field.value === ''))

/**
 * Filing by drag and drop (spec 6.5). It owns the upload queue, which lives for as long as the page does, so uploads go
 * on while the person moves about the app; the form that comes first when a required property has no default or Option
 * is held; the question a drop on one document's row asks; and the tray in the corner. Leaving the page while anything is
 * uploading asks first.
 */
export function FilingProvider({ children }: { children: ReactNode }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const uploads = useMemo(() => createUploads(api, { onSettled: () => void invalidateDocumentLists(queryClient) }), [api, queryClient])
  const items = useSyncExternalStore(uploads.subscribe, uploads.items)
  const [form, setForm] = useState<PendingForm | undefined>()
  const [version, setVersion] = useState<PendingVersion | undefined>()
  const busy = items.some((item) => item.status === 'waiting' || item.status === 'uploading')

  // A page left while a file is on its way loses it, so the browser asks first (it shows its own words)
  useEffect(() => {
    if (!busy) return
    const ask = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', ask)
    return () => window.removeEventListener('beforeunload', ask)
  }, [busy])

  async function fileInto(files: File[], place: Place, alt: boolean) {
    try {
      const answer = await queryClient.fetchQuery({
        queryKey: keys.form(place.id),
        queryFn: () => api.get<PageOf<S['ProfileField']>>(apiPath`/v1/folders/${place.id}/form`),
        staleTime: 0,
      })
      if (alt || lacksRequired(answer.items)) setForm({ files, place, fields: answer.items })
      else uploads.enqueue(named(files), place.id)
    } catch (error) {
      toast(messageFor(error))
    }
  }

  function start(files: File[], place: Place, alt: boolean, row?: S['DocumentView']) {
    const refusal = refusalOf(place)
    if (refusal) return toast(t(refusal))
    // One file on a document's row is a question; any other drop on a row is a drop on the folder
    if (row && files.length === 1) return setVersion({ file: files[0]!, document: row })
    void fileInto(files, place, alt)
  }

  const value: Filing = {
    items,
    uploads,
    drop: (event, place, row) => {
      const types = Array.from(event.dataTransfer.types)
      // The app's own rows are for the move to take
      if (carriesRows(types) && !carriesFiles(types)) return false
      event.preventDefault()
      // A drop's files and Option are read now: the browser empties the data transfer when the event ends
      const files = carriesFiles(types) ? Array.from(event.dataTransfer.files) : []
      if (files.length === 0) {
        toast(t('filing.noFiles'))
        return true
      }
      start(files, place, event.altKey, row)
      return true
    },
    pick: (files, place) => start(files, place, false),
  }

  return (
    <Context.Provider value={value}>
      {children}
      {form && (
        <FilingForm
          key={form.files.map((file) => file.name).join('/')}
          files={form.files}
          fields={form.fields}
          onFile={(files, properties) => {
            uploads.enqueue(files, form.place.id, properties)
            setForm(undefined)
          }}
          onCancel={() => setForm(undefined)}
        />
      )}
      {version && (
        <VersionOrNew
          document={version.document}
          file={version.file}
          onVersion={(ifHeld) => {
            uploads.enqueueVersion(version.document, version.file, ifHeld)
            setVersion(undefined)
          }}
          onNew={() => {
            const place: Place = { id: version.document.folder.id, kind: 'folder' }
            setVersion(undefined)
            void fileInto([version.file], place, false)
          }}
          onCancel={() => setVersion(undefined)}
        />
      )}
      <FilingTray />
    </Context.Provider>
  )
}

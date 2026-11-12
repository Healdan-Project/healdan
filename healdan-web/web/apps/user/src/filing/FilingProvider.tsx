// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore, type DragEvent, type ReactNode } from 'react'
import { apiPath, type components } from '@healdan/api'
import { t, toast } from '@healdan/ui'
import { invalidateDocumentLists } from '../actions/refresh'
import { messageFor } from '../frame/errors'
import type { PageOf } from '../pages'
import { keys } from '../queries'
import { useApi } from '../services'
import { carriesFiles, carriesRows, droppedFiles, refusalOf, sameExtension, takesDrag, type Place } from './dropTargets'
import { FilingForm } from './FilingForm'
import { FilingTray } from './FilingTray'
import { missingRequired } from './required'
import { coalesced, createUploads, isBusy, named, type UploadItem, type Uploads } from './uploads'
import { VersionOrNew } from './VersionOrNew'

type S = components['schemas']

export interface Filing {
  /** What the tray shows: every item since the page opened that hasn't been dismissed */
  items: readonly UploadItem[]
  uploads: Uploads
  /** Whether anything is waiting or uploading */
  busy: boolean
  /** The doc #s just filed, which their lists flash */
  fresh: ReadonlySet<number>
  /**
   * A drop on a folder, or on one document's row of its folder. It files what it carries, or says why it won't; a drop that
   * carries the app's own rows isn't the filing's, and is left alone.
   */
  drop: (event: DragEvent, place: Place, row?: S['DocumentView']) => void
  /** Files chosen with the Upload button; `alt` (Option held when it was pressed) shows the form first */
  pick: (files: File[], place: Place, alt?: boolean) => void
}

const Context = createContext<Filing | null>(null)

const labelsOf = (fields: S['ProfileField'][]) => Object.fromEntries(fields.map((field) => [field.name, field.label]))

export function useFiling(): Filing {
  const filing = useContext(Context)
  if (!filing) throw new Error('useFiling needs a FilingProvider')
  return filing
}

interface PendingForm {
  /** Its own, so the form that is open keeps its state while others queue behind it */
  id: number
  files: File[]
  place: Place
  fields: S['ProfileField'][]
  matter?: S['DocumentView']['matter']
}

interface PendingVersion {
  file: File
  document: S['DocumentView']
  /** Option was held, so "File as a New Document" asks for the form */
  alt: boolean
  /** Where it was dropped, which "File as a New Document" files into */
  place: Place
}

/** How often, at most, the lists are asked for again while the queue runs: once at once, then the rest gathered */
const REFRESH_MS = 1000

/**
 * Filing by drag and drop (spec 6.5). It owns the upload queue, which lives for as long as the page does, so uploads go
 * on while the person moves about the app; the form that comes first when a required property has no default or Option
 * is held; the question a drop on one document's row asks; and the tray in the corner. Leaving the page while anything is
 * uploading asks first.
 */
export function FilingProvider({ children }: { children: ReactNode }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const uploads = useMemo(
    () =>
      createUploads(api, {
        // Each item settling would ask for every list, the tree and the details again: a run of them asks once a second
        onSettled: coalesced(() => void invalidateDocumentLists(queryClient), REFRESH_MS),
        meId: () => queryClient.getQueryData<S['UserView']>(keys.me)?.id,
      }),
    [api, queryClient],
  )
  const items = useSyncExternalStore(uploads.subscribe, uploads.items)
  // Forms wait in turn: a drop that needs the form while another's is open keeps its files, and opens when that one closes
  const [forms, setForms] = useState<PendingForm[]>([])
  const nextForm = useRef(1)
  const form = forms[0]
  const [version, setVersion] = useState<PendingVersion | undefined>()
  const busy = isBusy(items)
  // The same set until what is fresh changes, so a list that flashes them isn't drawn again at each change to the queue
  const freshKey = items.flatMap((item) => (item.status === 'filed' && item.fresh && item.docNumber !== undefined ? [item.docNumber] : [])).join(',')
  const fresh = useMemo(() => new Set(freshKey === '' ? [] : freshKey.split(',').map(Number)), [freshKey])

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

  // A file dropped outside every target would be opened in place of the app, and a link followed, taking the queue with
  // it: the drag is refused here. A target has cancelled its own by now (React's handlers run before the window's), and a
  // field takes what is dropped in it.
  useEffect(() => {
    const outside = (event: globalThis.DragEvent) => {
      if (event.defaultPrevented || !takesDrag(Array.from(event.dataTransfer?.types ?? []))) return
      if (event.target instanceof Element && event.target.closest('input, textarea, select, [contenteditable="true"]')) return
      event.preventDefault()
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'none'
      if (event.type === 'drop') toast(t('filing.dropOnFolder'))
    }
    window.addEventListener('dragover', outside)
    window.addEventListener('drop', outside)
    return () => {
      window.removeEventListener('dragover', outside)
      window.removeEventListener('drop', outside)
    }
  }, [])

  async function fileInto(files: File[], place: Place, alt: boolean) {
    try {
      const answer = await queryClient.fetchQuery({
        queryKey: keys.form(place.id),
        queryFn: () => api.get<PageOf<S['ProfileField']>>(apiPath`/v1/folders/${place.id}/form`),
        staleTime: 0,
      })
      if (alt || missingRequired(answer.items).length > 0) {
        // The form shows the matter as the properties form does; the folder is almost always cached, and its absence isn't a reason not to file
        const where = await queryClient
          .fetchQuery({ queryKey: keys.folder(place.id), queryFn: () => api.get<S['FolderView']>(apiPath`/v1/folders/${place.id}`), staleTime: 60_000 })
          .catch(() => undefined)
        const id = nextForm.current++
        setForms((now) => [...now, { id, files, place, fields: answer.items, matter: where?.matter ?? undefined }])
      }
      else uploads.enqueue(named(files), place.id, undefined, { folderName: place.name, labels: labelsOf(answer.items) })
    } catch (error) {
      toast(messageFor(error))
    }
  }

  function start(files: File[], place: Place, alt: boolean, row?: S['DocumentView']) {
    const refusal = refusalOf(place)
    // One file on a document's row that could be its next version is a question; any other drop is a drop on the folder. A
    // version needs Write on the document, not on its folder, so one the person may change is asked about even where they
    // can't file (a document-only grant)
    if (row && files.length === 1 && sameExtension(files[0]!.name, row.name) && (!refusal || row.canEdit)) return setVersion({ file: files[0]!, document: row, alt, place })
    if (refusal) return toast(t(refusal))
    void fileInto(files, place, alt)
  }

  const value: Filing = {
    items,
    uploads,
    busy,
    fresh,
    drop: (event, place, row) => {
      const types = Array.from(event.dataTransfer.types)
      // The app's own rows are for the move to take
      if (carriesRows(types) && !carriesFiles(types)) return
      event.preventDefault()
      // A drop's files and Option are read now: the browser empties the data transfer when the event ends
      const { files, folders } = carriesFiles(types) ? droppedFiles(event.dataTransfer) : { files: [], folders: 0 }
      // A folder from Finder comes as a file the browser can't read: it's said, and the files beside it go on
      if (folders > 0) toast(t('filing.noFolders'))
      if (files.length === 0) {
        if (folders === 0) toast(t('filing.noFiles'))
        return
      }
      start(files, place, event.altKey, row)
    },
    pick: (files, place, alt = false) => start(files, place, alt),
  }

  return (
    <Context.Provider value={value}>
      {children}
      {form && (
        <FilingForm
          key={form.id}
          files={form.files}
          fields={form.fields}
          matter={form.matter}
          onFile={(files, properties) => {
            uploads.enqueue(files, form.place.id, properties, { folderName: form.place.name, labels: labelsOf(form.fields) })
            setForms((now) => now.slice(1))
          }}
          onCancel={() => setForms((now) => now.slice(1))}
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
            setVersion(undefined)
            // The question may have come where the person can't file (a document-only grant), so the folder's refusal is said now
            const refusal = refusalOf(version.place)
            if (refusal) toast(t(refusal))
            else void fileInto([version.file], version.place, version.alt)
          }}
          onCancel={() => setVersion(undefined)}
        />
      )}
      <FilingTray />
    </Context.Provider>
  )
}

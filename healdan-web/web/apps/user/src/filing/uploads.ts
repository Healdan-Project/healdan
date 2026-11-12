// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { ApiError, apiPath, type Api, type components } from '@healdan/api'
import { saveVersion } from '../actions/checkout'
import { copyOf } from './refusal'

type S = components['schemas']

/** A file to file, with the title it's filed under (its name, with its extension) */
export interface FilingFile {
  file: File
  name: string
}

export function named(files: File[]): FilingFile[] {
  return files.map((file) => ({ file, name: file.name }))
}

export type UploadStatus = 'waiting' | 'uploading' | 'filed' | 'refused'

export interface UploadItem {
  id: number
  /** The title it's filed under, or the name of the document it's saved into */
  name: string
  status: UploadStatus
  kind: 'new' | 'version'
  /** The folder it's filed into, and what the tray calls it */
  folderId: number
  folderName?: string
  /** The labels of the form's properties, by name, to say which one a refusal is about */
  labels?: Record<string, string>
  /** Filed, or the version's document, once it's done */
  docNumber?: number
  /** The version a save made */
  version?: string
  /** Why it was refused */
  problem?: unknown
  /** For `already-filed`: the copy the matter holds */
  existing?: S['DocumentView']
  /** Just filed: the list flashes it for a few seconds */
  fresh?: boolean
}

type Job = { kind: 'new'; file: FilingFile; properties?: Record<string, S['PropertyValue']> } | { kind: 'version'; document: S['DocumentView']; file: File; ifHeld: boolean }

export interface Uploads {
  /** The items in the order they were made; the same array until something changes */
  items: () => readonly UploadItem[]
  subscribe: (listener: () => void) => () => void
  /** Whether anything is waiting or uploading */
  busy: () => boolean
  enqueue: (files: FilingFile[], folderId: number, properties?: Record<string, S['PropertyValue']>, extra?: { folderName?: string; labels?: Record<string, string> }) => void
  /** Saves a file as the next version of a document; `ifHeld` when the document is the person's own web check-out */
  enqueueVersion: (document: S['DocumentView'], file: File, ifHeld: boolean) => void
  /** Removes a finished item */
  dismiss: (id: number) => void
  /** Removes every finished item and keeps the running ones */
  clearFinished: () => void
}

/** Whether any item is waiting or uploading: the one meaning of "busy" */
export function isBusy(items: readonly UploadItem[]): boolean {
  return items.some((item) => item.status === 'waiting' || item.status === 'uploading')
}

export interface UploadsOptions {
  /** Called after each item is done, however it went: the lists that show documents are asked for again */
  onSettled: () => void
  /** How many run at once; 3 by default */
  limit?: number
  /** How long a new document is fresh, in ms */
  freshFor?: number
  /** The signed-in person's id, if it's known: only their own check-out a failed save left is released */
  meId?: () => number | undefined
}

/**
 * Calls `call` at once, then at most once each `ms`: the calls in between are gathered into one at the end of the
 * interval, so a burst of them costs two.
 */
export function coalesced(call: () => void, ms: number): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined
  let again = false
  const wait = () => {
    timer = setTimeout(() => {
      timer = undefined
      if (!again) return
      again = false
      call()
      wait()
    }, ms)
  }
  return () => {
    if (timer !== undefined) {
      again = true
      return
    }
    call()
    wait()
  }
}

/**
 * Whether a save that failed may have left the check-out the server took for it: a rule that refused the check-in
 * (`already-filed`, `refused`), or a failure the answer doesn't explain (a server error, or no answer). The other
 * refusals (a hold, a precondition, the name, the file) come before the server checks anything out.
 */
function mayLeaveCheckOut(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true
  return error.code === 'already-filed' || error.code === 'refused' || error.status >= 500
}

/** How many uploads run at once: a person's connection is better used by a few than many */
const AT_ONCE = 3
const FRESH_MS = 4000

/**
 * The queue of filing (spec 6.5): items go in order, at most three at a time, each through the API as its own request,
 * so a refusal of one never stops the others. It lives in memory for as long as the page does, so uploads go on while
 * the person moves about the app; it keeps nothing in the browser's storage.
 */
export function createUploads(api: Api, options: UploadsOptions): Uploads {
  const limit = options.limit ?? AT_ONCE
  let items: readonly UploadItem[] = []
  const jobs = new Map<number, Job>()
  const listeners = new Set<() => void>()
  let nextId = 1

  function change(update: (now: readonly UploadItem[]) => readonly UploadItem[]) {
    items = update(items)
    for (const listener of [...listeners]) listener()
  }

  function patch(id: number, over: Partial<UploadItem>) {
    change((now) => now.map((item) => (item.id === id ? { ...item, ...over } : item)))
  }

  async function send(item: UploadItem, job: Job): Promise<S['DocumentView']> {
    if (job.kind === 'version') return saveVersion(api, job.document, job.file, { kind: 'version', ifHeld: job.ifHeld })
    const form = new FormData()
    const document = { folderId: item.folderId, name: job.file.name, properties: job.properties ?? null, checkOut: false }
    form.append('document', new Blob([JSON.stringify(document)], { type: 'application/json' }))
    form.append('file', job.file.file, job.file.file.name)
    return api.send('POST', '/v1/documents', form)
  }

  async function run(item: UploadItem) {
    const job = jobs.get(item.id)!
    patch(item.id, { status: 'uploading' })
    try {
      const done = await send(item, job)
      patch(item.id, { status: 'filed', docNumber: done.docNumber, version: job.kind === 'version' ? done.version : undefined, fresh: true })
      setTimeout(() => patch(item.id, { fresh: false }), options.freshFor ?? FRESH_MS)
    } catch (error) {
      patch(item.id, { status: 'refused', problem: error, existing: copyOf(error) })
      // The server leaves the document checked out to the person when a rule refuses the check-in itself, or the save fails
      // after its check-out, even if it was free before. They never asked for that, so it's released, unless it was theirs
      // already; any other refusal changes nothing
      if (job.kind === 'version' && !job.ifHeld && mayLeaveCheckOut(error)) await releaseLeftover(job.document.docNumber).catch(() => undefined)
    } finally {
      jobs.delete(item.id)
      options.onSettled()
      pump()
    }
  }

  /**
   * Releases the person's own check-out from this page, which the failed save took. A failure can also come after someone
   * else took the document (an administrator's release would end theirs), so it's read first.
   */
  async function releaseLeftover(docNumber: number) {
    const me = options.meId?.()
    const now = await api.get<S['DocumentView']>(apiPath`/v1/documents/${docNumber}`)
    if (me === undefined || now.hold?.kind !== 'checkout' || now.hold.device || now.hold.by?.id !== me) return
    await api.send('DELETE', apiPath`/v1/documents/${docNumber}/checkout`)
  }

  function pump() {
    let running = items.filter((item) => item.status === 'uploading').length
    for (const item of items) {
      if (running >= limit) return
      if (item.status === 'waiting' && jobs.has(item.id)) {
        running++
        void run(item)
      }
    }
  }

  function add(entries: { name: string; folderId: number; folderName?: string; labels?: Record<string, string>; job: Job }[]) {
    const made = entries.map(({ name, folderId, folderName, labels, job }) => {
      const item: UploadItem = { id: nextId++, name, status: 'waiting', kind: job.kind, folderId, folderName, labels }
      jobs.set(item.id, job)
      return item
    })
    change((now) => [...now, ...made])
    pump()
  }

  return {
    items: () => items,
    subscribe: (listener) => {
      listeners.add(listener)
      return () => void listeners.delete(listener)
    },
    busy: () => isBusy(items),
    enqueue: (files, folderId, properties, extra) =>
      add(files.map((file) => ({ name: file.name, folderId, folderName: extra?.folderName, labels: extra?.labels, job: { kind: 'new', file, properties } }))),
    enqueueVersion: (document, file, ifHeld) => add([{ name: document.name, folderId: document.folder.id, folderName: document.folder.name, job: { kind: 'version', document, file, ifHeld } }]),
    dismiss: (id) => change((now) => now.filter((item) => item.id !== id || isBusy([item]))),
    clearFinished: () => change((now) => now.filter((item) => isBusy([item]))),
  }
}

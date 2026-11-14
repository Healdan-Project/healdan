// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@healdan/api'
import { document, fakeApi, problem } from '../test/fakeApi'
import { coalesced, createUploads, named } from './uploads'

const file = (name: string, text = 'x') => new File([text], name)
const filed = (docNumber: number, name: string) => document({ docNumber, name })

/** A route whose answers the test lets go one by one */
function gate() {
  const waiting: { resolve: (value: unknown) => void; reject: (error: unknown) => void; body: FormData }[] = []
  return {
    waiting,
    handler: ({ body }: { body?: unknown }) => new Promise((resolve, reject) => waiting.push({ resolve, reject, body: body as FormData })),
  }
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('uploads', () => {
  it('files each item as a new document with the folder, the title and the form\'s values, and keeps its number', async () => {
    let next = 10500
    const api = fakeApi({ 'POST /v1/documents': ({ body }: { body?: unknown }) => filed(next++, ((body as FormData).get('file') as File).name) })
    const onSettled = vi.fn()
    const uploads = createUploads(api, { onSettled })

    uploads.enqueue([{ file: file('a.pdf'), name: 'Lease.pdf' }, ...named([file('b.docx')])], 7, { docclass: 'Letters' })
    await vi.waitFor(() => expect(uploads.items().every((item) => item.status === 'filed')).toBe(true))

    expect(uploads.items().map((item) => [item.name, item.status, item.docNumber])).toEqual([
      ['Lease.pdf', 'filed', 10500],
      ['b.docx', 'filed', 10501],
    ])
    const [first] = api.callsTo('POST /v1/documents')
    expect(first).toBeTruthy()
    expect(onSettled).toHaveBeenCalledTimes(2)
  })

  it('sends the document part as JSON with the folder, name, properties and no check-out', async () => {
    let sent: FormData | undefined
    const api = fakeApi({ 'POST /v1/documents': ({ body }: { body?: unknown }) => ((sent = body as FormData), filed(1, 'x')) })
    createUploads(api, { onSettled: () => {} }).enqueue([{ file: file('a.pdf'), name: 'Lease.pdf' }], 7, { docclass: 'Letters' })
    await vi.waitFor(() => expect(sent).toBeTruthy())
    expect(JSON.parse(await (sent!.get('document') as Blob).text())).toEqual({ folderId: 7, name: 'Lease.pdf', properties: { docclass: 'Letters' }, checkOut: false })
    expect((sent!.get('file') as File).name).toBe('a.pdf')
  })

  it('runs at most three at once, and starts the next as one ends', async () => {
    const g = gate()
    const uploads = createUploads(fakeApi({ 'POST /v1/documents': g.handler }), { onSettled: () => {} })
    uploads.enqueue(named([1, 2, 3, 4, 5].map((n) => file(`f${n}.pdf`))), 7)
    await settle()
    expect(g.waiting).toHaveLength(3)
    expect(uploads.items().map((item) => item.status)).toEqual(['uploading', 'uploading', 'uploading', 'waiting', 'waiting'])

    g.waiting[0]!.resolve(filed(1, 'f1.pdf'))
    await settle()
    expect(g.waiting).toHaveLength(4)
    expect(uploads.busy()).toBe(true)
  })

  it('shows a refusal against its item and files the others', async () => {
    const api = fakeApi({
      'POST /v1/documents': ({ body }: { body?: unknown }) => {
        const name = ((body as FormData).get('file') as File).name
        return name === 'huge.zip' ? problem(413, 'too-large') : filed(20, name)
      },
    })
    const uploads = createUploads(api, { onSettled: () => {} })
    uploads.enqueue(named([file('huge.zip'), file('ok.pdf')]), 7)
    await vi.waitFor(() => expect(uploads.busy()).toBe(false))
    const [huge, ok] = uploads.items()
    expect(huge!.status).toBe('refused')
    expect((huge!.problem as ApiError).code).toBe('too-large')
    expect(ok!.status).toBe('filed')
  })

  it('keeps the copy an email that is already in the matter is filed as', async () => {
    const copy = document({ docNumber: 10455, name: 'Re Lease.eml' })
    const api = fakeApi({ 'POST /v1/documents': new ApiError(409, 'already-filed', 'x', { code: 'already-filed', document: copy }) })
    const uploads = createUploads(api, { onSettled: () => {} })
    uploads.enqueue(named([file('Re Lease.eml')]), 7)
    await vi.waitFor(() => expect(uploads.busy()).toBe(false))
    expect(uploads.items()[0]).toMatchObject({ status: 'refused', existing: copy })
  })

  it('saves a file as the next version of a document through POST /versions, under the document\'s name', async () => {
    let sent: FormData | undefined
    const api = fakeApi({ 'POST /v1/documents/10431/versions': ({ body }: { body?: unknown }) => ((sent = body as FormData), document({ docNumber: 10431, version: '4.0', fileVersion: '4.0' })) })
    const uploads = createUploads(api, { onSettled: () => {} })
    const target = document({ docNumber: 10431, name: 'Letter.docx', version: '3.1' })
    uploads.enqueueVersion(target, file('draft.docx'), false)
    await vi.waitFor(() => expect(uploads.busy()).toBe(false))
    expect(JSON.parse(await (sent!.get('version') as Blob).text())).toEqual({ kind: 'version', name: 'Letter.docx', ifHeld: false, ifVersion: '3.1' })
    expect(uploads.items()[0]).toMatchObject({ status: 'filed', docNumber: 10431, version: '4.0' })
  })

  it('dismisses a finished item and never a running one', async () => {
    const g = gate()
    const uploads = createUploads(fakeApi({ 'POST /v1/documents': g.handler }), { onSettled: () => {} })
    uploads.enqueue(named([file('a.pdf')]), 7)
    await settle()
    const id = uploads.items()[0]!.id
    uploads.dismiss(id)
    expect(uploads.items()).toHaveLength(1)
    g.waiting[0]!.resolve(filed(1, 'a.pdf'))
    await vi.waitFor(() => expect(uploads.busy()).toBe(false))
    uploads.dismiss(id)
    expect(uploads.items()).toHaveLength(0)
  })

  it('tells a subscriber each change, and a new item is fresh for a while', async () => {
    const api = fakeApi({ 'POST /v1/documents': filed(1, 'a.pdf') })
    const uploads = createUploads(api, { onSettled: () => {} })
    const seen = vi.fn()
    uploads.subscribe(seen)
    uploads.enqueue(named([file('a.pdf')]), 7)
    await vi.waitFor(() => expect(uploads.busy()).toBe(false))
    expect(seen).toHaveBeenCalled()
    expect(uploads.items()[0]!.fresh).toBe(true)
  })
})

describe('a version the server refuses', () => {
  const target = document({ docNumber: 10431, name: 'Letter.docx', version: '3.1' })
  const refusal = () => new ApiError(409, 'already-filed', 'x', { code: 'already-filed' })
  const me = 5
  // What the save left: the document checked out to the person from this page, which is no device
  const leftMine = document({ docNumber: 10431, hold: { kind: 'checkout', by: { id: me, name: 'Priya Nair' }, since: '2026-11-03T10:00:00Z', device: null, heldOn: null } })
  const theirs = document({ docNumber: 10431, hold: { kind: 'checkout', by: { id: 3, name: 'Jamie Clarke' }, since: '2026-11-03T10:00:00Z', device: null, heldOn: null } })

  it('releases the check-out the refusal left behind when the person had none before', async () => {
    const api = fakeApi({ 'POST /v1/documents/10431/versions': refusal(), 'GET /v1/documents/10431': leftMine, 'DELETE /v1/documents/10431/checkout': document({ docNumber: 10431 }) })
    const uploads = createUploads(api, { onSettled: () => {}, meId: () => me })
    uploads.enqueueVersion(target, file('d.docx'), false)
    await vi.waitFor(() => expect(uploads.busy()).toBe(false))
    await vi.waitFor(() => expect(api.callsTo('DELETE /v1/documents/10431/checkout')).toHaveLength(1))
    expect(uploads.items()[0]).toMatchObject({ status: 'refused', kind: 'version' })
  })

  it('releases it too after a failure the answer doesn\'t explain, which can come after the server\'s check-out', async () => {
    for (const failure of [problem(500, 'server-error'), new TypeError('Failed to fetch')]) {
      const api = fakeApi({ 'POST /v1/documents/10431/versions': failure, 'GET /v1/documents/10431': leftMine, 'DELETE /v1/documents/10431/checkout': document({ docNumber: 10431 }) })
      const uploads = createUploads(api, { onSettled: () => {}, meId: () => me })
      uploads.enqueueVersion(target, file('d.docx'), false)
      await vi.waitFor(() => expect(api.callsTo('DELETE /v1/documents/10431/checkout')).toHaveLength(1))
    }
  })

  it('leaves a check-out someone else took before the save failed, or one it can\'t tell is the person\'s', async () => {
    for (const [now, meId] of [[theirs, () => me], [leftMine, () => undefined]] as const) {
      const api = fakeApi({ 'POST /v1/documents/10431/versions': problem(500, 'server-error'), 'GET /v1/documents/10431': now })
      const uploads = createUploads(api, { onSettled: () => {}, meId })
      uploads.enqueueVersion(target, file('d.docx'), false)
      await vi.waitFor(() => expect(api.callsTo('GET /v1/documents/10431')).toHaveLength(1))
      await settle()
      expect(api.callsTo('DELETE /v1/documents/10431/checkout')).toHaveLength(0)
    }
  })

  it('leaves a check-out the person already had', async () => {
    const api = fakeApi({ 'POST /v1/documents/10431/versions': refusal() })
    const uploads = createUploads(api, { onSettled: () => {} })
    uploads.enqueueVersion(target, file('d.docx'), true)
    await vi.waitFor(() => expect(uploads.busy()).toBe(false))
    expect(api.callsTo('DELETE /v1/documents/10431/checkout')).toHaveLength(0)
  })

  it('never releases a check-out that is someone else\'s', async () => {
    const api = fakeApi({ 'POST /v1/documents/10431/versions': new ApiError(409, 'checked-out', 'x', { code: 'checked-out' }) })
    const uploads = createUploads(api, { onSettled: () => {} })
    uploads.enqueueVersion(target, file('d.docx'), false)
    await vi.waitFor(() => expect(uploads.busy()).toBe(false))
    expect(api.callsTo('DELETE /v1/documents/10431/checkout')).toHaveLength(0)
  })
})

describe('coalesced', () => {
  it('calls at once, then gathers the calls of each interval into one at its end', () => {
    vi.useFakeTimers()
    try {
      const call = vi.fn()
      const refresh = coalesced(call, 1000)
      refresh()
      expect(call).toHaveBeenCalledTimes(1)
      refresh()
      refresh()
      refresh()
      expect(call).toHaveBeenCalledTimes(1)
      vi.advanceTimersByTime(1000)
      expect(call).toHaveBeenCalledTimes(2)
      // Nothing more came, so nothing more is called, and the next one is called at once again
      vi.advanceTimersByTime(1000)
      expect(call).toHaveBeenCalledTimes(2)
      refresh()
      expect(call).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
  })
})

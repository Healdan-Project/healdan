// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { document, problem } from '../test/fakeApi'
import { renderPreview } from '../test/renderPreview'
import { PictureView } from './PictureView'

const scan = document({ name: 'Scan.png' })
const path = 'GET /v1/documents/10388/preview'
const png = () => new Response(new Uint8Array([0x89, 0x50, 0x4e, 0x47]), { status: 200, headers: { 'Content-Type': 'image/png' } })

let made = 0
const create = vi.fn(() => `blob:picture-${++made}`)
const revoke = vi.fn()

beforeEach(() => {
  made = 0
  create.mockClear()
  revoke.mockClear()
  Object.defineProperty(URL, 'createObjectURL', { value: create, configurable: true, writable: true })
  Object.defineProperty(URL, 'revokeObjectURL', { value: revoke, configurable: true, writable: true })
})
afterEach(() => {
  // @ts-expect-error jsdom has neither, so they go back to nothing
  delete URL.createObjectURL
  // @ts-expect-error as above
  delete URL.revokeObjectURL
})

describe('PictureView', () => {
  it("shows a picture from the preview, as the file's own bytes, in a blob: address", async () => {
    const { api } = renderPreview(<PictureView document={scan} />, { [path]: png })
    const picture = await screen.findByRole('img', { name: 'Picture: Scan.png' })
    expect(picture.getAttribute('src')).toMatch(/^blob:picture-/)
    expect(api.callsTo(path)).toHaveLength(1)
    // Never the content, which the server records as a download
    expect(api.callsTo('GET /v1/documents/10388/content')).toHaveLength(0)
  })

  it('asks for the version it is given', async () => {
    const { api } = renderPreview(<PictureView document={scan} version="1.0" />, { [path]: png })
    await screen.findByRole('img', { name: 'Picture: Scan.png' })
    expect(api.callsTo(path)[0]?.query).toEqual({ version: '1.0' })
  })

  it('revokes the address when the picture goes away, and keeps exactly one while it is shown', async () => {
    const { unmount } = renderPreview(<PictureView document={scan} />, { [path]: png })
    await screen.findByRole('img', { name: 'Picture: Scan.png' })
    // Net of the double start of effects in React's strict mode
    expect(create.mock.calls.length - revoke.mock.calls.length).toBe(1)
    unmount()
    expect(revoke.mock.calls.length).toBe(create.mock.calls.length)
  })

  it('says there is no preview when the browser cannot draw the picture, as for a TIFF in Chrome', async () => {
    renderPreview(<PictureView document={document({ name: 'Scan.tif' })} />, { [path]: png })
    fireEvent.error(await screen.findByRole('img'))
    expect(await screen.findByText("There's no preview for this kind of file.")).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Download' })).toBeTruthy()
  })

  it('says it for a picture the person may not preview, as for one that is not there', async () => {
    renderPreview(<PictureView document={scan} />, { [path]: problem(403, 'forbidden') })
    expect(await screen.findByText("There's no preview for this kind of file.")).toBeTruthy()
  })

  it('offers Try Again for any other failure', async () => {
    renderPreview(<PictureView document={scan} />, { [path]: problem(503, 'storage-unreachable') })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Try Again' })).toBeTruthy())
  })

  it('passes axe', async () => {
    const { container } = renderPreview(<PictureView document={scan} />, { [path]: png })
    await screen.findByRole('img')
    await expectNoAxeViolations(container)
  })
})

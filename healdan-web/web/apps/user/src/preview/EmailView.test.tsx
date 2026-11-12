// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { formatDateTime, setLabels } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { document, messageView, problem } from '../test/fakeApi'
import { resetPdf } from '../test/fakePdf'
import { pdfResponse, renderPreview } from '../test/renderPreview'
import { invalidateDocumentLists } from '../actions/refresh'
import { EmailView } from './EmailView'

vi.mock('./pdfEngine', () => import('../test/fakePdf'))

const mail = document({ name: 'Re Charter Dispute.eml' })
const message = 'GET /v1/documents/10388/message'
const part = 'GET /v1/documents/10388/message/parts/*'
const save = 'POST /v1/documents/10388/message/attachments/*/file'

beforeEach(() => {
  resetPdf()
  Object.defineProperty(URL, 'createObjectURL', { value: () => 'blob:attachment', configurable: true, writable: true })
  Object.defineProperty(URL, 'revokeObjectURL', { value: () => {}, configurable: true, writable: true })
})
afterEach(() => {
  // @ts-expect-error jsdom has neither, so they go back to nothing
  delete URL.createObjectURL
  // @ts-expect-error as above
  delete URL.revokeObjectURL
})

const png = () => new Response(new Uint8Array([0x89, 0x50]), { status: 200, headers: { 'Content-Type': 'image/png' } })

describe('EmailView', () => {
  it('shows the subject, the sender, To and Cc, and when it was sent and received', async () => {
    renderPreview(<EmailView document={mail} />, { [message]: messageView() })

    expect(await screen.findByRole('heading', { name: 'Re: Charter Dispute' })).toBeTruthy()
    const details = screen.getByRole('group', { name: 'Message Details' })
    expect(within(details).getByText('Dunmere Pryce <dunmere@pryce.example>')).toBeTruthy()
    expect(within(details).getByText('Priya Nair <priya@example.test>')).toBeTruthy()
    expect(within(details).getByText('Jamie Clarke <jclarke@example.test>')).toBeTruthy()
    expect(within(details).getByText(formatDateTime('2026-11-03T10:00:00Z'))).toBeTruthy()
    expect(within(details).getByText(formatDateTime('2026-11-03T10:07:05Z'))).toBeTruthy()
    for (const label of ['From', 'To', 'Cc', 'Sent', 'Received']) expect(within(details).getByText(label)).toBeTruthy()
  })

  it('reads the message once for its file version, not again when the lists are refreshed, and again for a new version', async () => {
    function Saved() {
      const [fileVersion, setFileVersion] = useState(mail.fileVersion)
      return (
        <>
          <button type="button" onClick={() => setFileVersion('1.1')}>
            Saved Again
          </button>
          <EmailView document={{ ...mail, fileVersion }} />
        </>
      )
    }
    const { api, queryClient } = renderPreview(<Saved />, { [message]: messageView() })
    await screen.findByRole('heading', { name: 'Re: Charter Dispute' })
    // An upload's finish, a rename or a check-out elsewhere refreshes every list
    await act(() => invalidateDocumentLists(queryClient))
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(api.callsTo(message)).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Saved Again' }))
    await waitFor(() => expect(api.callsTo(message)).toHaveLength(2))
  })

  it('leaves out what the message does not have, and says when it has no subject', async () => {
    renderPreview(<EmailView document={mail} />, { [message]: messageView({ subject: null, cc: null, received: null, attachments: [] }) })

    expect(await screen.findByRole('heading', { name: '(No Subject)' })).toBeTruthy()
    expect(screen.queryByText('Cc')).toBeNull()
    expect(screen.queryByText('Received')).toBeNull()
    expect(screen.queryByText('Attachments')).toBeNull()
  })

  it('shows the body in a frame without scripts or an origin, pointing at the body, which has no pictures from the internet', async () => {
    renderPreview(<EmailView document={mail} />, { [message]: messageView() })

    const frame = await screen.findByTitle('Message Body')
    expect(frame.getAttribute('src')).toBe('/api/v1/documents/10388/message/body')
    const sandbox = frame.getAttribute('sandbox') ?? ''
    expect(sandbox).toBe('allow-popups allow-popups-to-escape-sandbox')
    expect(sandbox).not.toMatch(/allow-scripts|allow-same-origin/)
    expect(screen.getByText('Pictures from the internet are blocked.')).toBeTruthy()
  })

  it('reloads the frame with the pictures when told to, for this viewing only', async () => {
    renderPreview(<EmailView document={mail} />, { [message]: messageView() })
    await screen.findByTitle('Message Body')

    fireEvent.click(screen.getByRole('button', { name: 'Show Pictures' }))

    expect(screen.getByTitle('Message Body').getAttribute('src')).toBe('/api/v1/documents/10388/message/body?pictures=true')
    expect(screen.queryByRole('button', { name: 'Show Pictures' })).toBeNull()
    expect(screen.getByText('Showing pictures from the internet.')).toBeTruthy()
  })

  it('starts again without pictures for another email', async () => {
    const first = renderPreview(<EmailView key="a" document={mail} />, { [message]: messageView() })
    await screen.findByTitle('Message Body')
    fireEvent.click(screen.getByRole('button', { name: 'Show Pictures' }))
    first.unmount()

    renderPreview(<EmailView key="b" document={mail} />, { [message]: messageView() })
    expect((await screen.findByTitle('Message Body')).getAttribute('src')).toBe('/api/v1/documents/10388/message/body')
  })

  it('lists the attachments as chips with their sizes', async () => {
    renderPreview(<EmailView document={mail} />, { [message]: messageView() })

    const list = await screen.findByRole('list', { name: 'Attachments' })
    const chips = within(list).getAllByRole('listitem')
    expect(chips).toHaveLength(3)
    expect(within(chips[0]!).getByText('Contract.pdf')).toBeTruthy()
    expect(within(chips[0]!).getByText('117 KB')).toBeTruthy()
  })

  it('previews a PDF chip in place of the body, and Back to Message brings the body back', async () => {
    const { api } = renderPreview(<EmailView document={mail} />, { [message]: messageView(), [part]: () => pdfResponse() })
    fireEvent.click(await screen.findByRole('button', { name: 'Preview Contract.pdf' }))

    expect(await screen.findByText('Page 1 of 3')).toBeTruthy()
    expect(screen.queryByTitle('Message Body')).toBeNull()
    const asked = api.callsTo(part)[0]
    expect(asked?.path).toBe('/v1/documents/10388/message/parts/1')
    expect(asked?.query).toEqual({ inline: 'true' })

    fireEvent.click(screen.getByRole('button', { name: 'Back to Message' }))
    expect(await screen.findByTitle('Message Body')).toBeTruthy()
    expect(screen.queryByText('Page 1 of 3')).toBeNull()
  })

  it('previews a picture chip as a picture, described by the attachment', async () => {
    const { api } = renderPreview(<EmailView document={mail} />, { [message]: messageView(), [part]: png })
    fireEvent.click(await screen.findByRole('button', { name: 'Preview Site Photo.png' }))

    expect(await screen.findByRole('img', { name: 'Picture: Site Photo.png' })).toBeTruthy()
    expect(api.callsTo(part)[0]?.path).toBe('/v1/documents/10388/message/parts/2')
    // Never the download, which the server records
    expect(api.callsTo(part)[0]?.query).toEqual({ inline: 'true' })
  })

  it('downloads anything else, as a link to the attachment, with no preview asked for', async () => {
    const { api } = renderPreview(<EmailView document={mail} />, { [message]: messageView() })

    const link = await screen.findByRole('link', { name: 'Download Schedule.xlsx' })

    expect(link.getAttribute('href')).toBe('/api/v1/documents/10388/message/parts/3')
    expect(link.hasAttribute('download')).toBe(true)
    expect(screen.queryByRole('button', { name: 'Preview Schedule.xlsx' })).toBeNull()
    expect(api.callsTo(part)).toHaveLength(0)
  })

  it('previews a PDF or a picture by its name when the sender sent it as a plain file, and downloads a TIFF', async () => {
    const attachments = [
      { index: 4, name: 'Report.pdf', type: 'application/octet-stream', size: 1_000, message: false, docNumber: null },
      { index: 5, name: 'Logo.JPG', type: 'application/octet-stream', size: 1_000, message: false, docNumber: null },
      // Most browsers can't draw a TIFF, and Safari draws only its first page
      { index: 6, name: 'Fax.tif', type: 'image/tiff', size: 1_000, message: false, docNumber: null },
    ]
    renderPreview(<EmailView document={mail} />, { [message]: messageView({ attachments }) })

    expect(await screen.findByRole('button', { name: 'Preview Report.pdf' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Preview Logo.JPG' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Download Fax.tif' }).getAttribute('href')).toBe('/api/v1/documents/10388/message/parts/6')
    expect(screen.queryByRole('button', { name: 'Preview Fax.tif' })).toBeNull()
  })

  it('offers Download beside Back to Message for the attachment shown, whether or not it could be drawn', async () => {
    renderPreview(<EmailView document={mail} />, { [message]: messageView(), [part]: () => problem(404, 'no-preview') })
    fireEvent.click(await screen.findByRole('button', { name: 'Preview Contract.pdf' }))

    expect(await screen.findByText("There's no preview for this kind of file.")).toBeTruthy()
    const link = screen.getByRole('link', { name: 'Download Contract.pdf' })
    expect(link.getAttribute('href')).toBe('/api/v1/documents/10388/message/parts/1')
    expect(link.getAttribute('download')).toBe('Contract.pdf')
  })

  it('offers no Download of the attachment shown to someone who may not download the email', async () => {
    renderPreview(<EmailView document={{ ...mail, canOpen: false }} />, { [message]: messageView(), [part]: () => pdfResponse() })
    fireEvent.click(await screen.findByRole('button', { name: 'Preview Contract.pdf' }))

    await screen.findByText('Page 1 of 3')
    expect(screen.queryByRole('link', { name: 'Download Contract.pdf' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Download Contract.pdf' }).hasAttribute('disabled')).toBe(true)
  })

  it("reads a version's message, its body, its attachments and Save to Matter", async () => {
    const versioned = 'POST /v1/documents/10388/message/attachments/1/file?version=1.0'
    const { api } = renderPreview(<EmailView document={mail} version="1.0" />, {
      [message]: messageView(),
      [part]: () => pdfResponse(),
      [versioned]: () => document({ docNumber: 10500, name: 'Contract.pdf' }),
    })

    expect((await screen.findByTitle('Message Body')).getAttribute('src')).toBe('/api/v1/documents/10388/message/body?version=1.0')
    expect(api.callsTo(message)[0]?.query).toEqual({ version: '1.0' })
    expect(screen.getByRole('link', { name: 'Download Schedule.xlsx' }).getAttribute('href')).toBe('/api/v1/documents/10388/message/parts/3?version=1.0')
    fireEvent.click(screen.getByRole('button', { name: 'Save to Matter: Contract.pdf' }))
    await waitFor(() => expect(api.callsTo(versioned)).toHaveLength(1))

    fireEvent.click(screen.getByRole('button', { name: 'Preview Contract.pdf' }))
    await screen.findByText('Page 1 of 3')
    expect(api.callsTo(part)[0]?.query).toEqual({ inline: 'true', version: '1.0' })
  })

  it('saves an attachment to the matter, and its chip then shows its #', async () => {
    let filed: number | null = null
    const { api } = renderPreview(<EmailView document={mail} />, {
      [message]: () => messageView({ attachments: messageView().attachments.map((a) => (a.index === 1 ? { ...a, docNumber: filed } : a)) }),
      [save]: () => {
        filed = 10500
        return document({ docNumber: 10500, name: 'Contract.pdf' })
      },
    })

    fireEvent.click(await screen.findByRole('button', { name: 'Save to Matter: Contract.pdf' }))

    expect(await screen.findByText('Saved as # 10500')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Save to Matter: Contract.pdf' })).toBeNull()
    expect(api.callsTo(save)).toHaveLength(1)
    expect(api.callsTo(save)[0]?.path).toBe('/v1/documents/10388/message/attachments/1/file')
    // The others can still be saved
    expect(screen.getByRole('button', { name: 'Save to Matter: Site Photo.png' })).toBeTruthy()
  })

  it('keeps the focus in the chip when the Save to Matter it was on gives way to the #', async () => {
    let filed: number | null = null
    renderPreview(<EmailView document={mail} />, {
      [message]: () => messageView({ attachments: messageView().attachments.map((a) => (a.index === 1 ? { ...a, docNumber: filed } : a)) }),
      [save]: () => {
        filed = 10500
        return document({ docNumber: 10500, name: 'Contract.pdf' })
      },
    })

    const button = await screen.findByRole('button', { name: 'Save to Matter: Contract.pdf' })
    act(() => button.focus())
    fireEvent.click(button)

    expect(await screen.findByText('Saved as # 10500')).toBeTruthy()
    // Not the page's body, from where Esc reaches no dialog: the attachment's own control, in the same chip
    await waitFor(() => expect(window.document.activeElement).toBe(screen.getByRole('button', { name: 'Preview Contract.pdf' })))
  })

  it('shows the # of an attachment that was filed already', async () => {
    const attachments = messageView().attachments.map((a) => (a.index === 3 ? { ...a, docNumber: 10412 } : a))
    renderPreview(<EmailView document={mail} />, { [message]: messageView({ attachments }) })

    expect(await screen.findByText('Saved as # 10412')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Save to Matter: Schedule.xlsx' })).toBeNull()
  })

  it("says Save to the firm's own word for a matter", async () => {
    setLabels({ matter: 'Project', matters: 'Projects' })
    renderPreview(<EmailView document={mail} />, { [message]: messageView() })
    const save = await screen.findByRole('button', { name: 'Save to Project: Contract.pdf' })
    expect(save.textContent).toBe('Save to Project')
  })

  it('keeps Save to Matter when saving fails', async () => {
    renderPreview(<EmailView document={mail} />, { [message]: messageView(), [save]: problem(409, 'already-filed') })

    fireEvent.click(await screen.findByRole('button', { name: 'Save to Matter: Contract.pdf' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Save to Matter: Contract.pdf' })).toBeTruthy())
    expect(screen.queryByText(/Saved as/)).toBeNull()
  })

  it('says there is no preview for a document that is not an email, with Open and Download', async () => {
    renderPreview(<EmailView document={mail} />, { [message]: problem(404, 'not-an-email') })

    expect(await screen.findByText("There's no preview for this kind of file.")).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Download' })).toBeTruthy()
  })

  it('offers Try Again when the message cannot be read for another reason', async () => {
    renderPreview(<EmailView document={mail} />, { [message]: problem(503, 'storage-unreachable') })

    await waitFor(() => expect(screen.getByRole('button', { name: 'Try Again' })).toBeTruthy())
  })

  it('offers no download and no Save to Matter to someone who may not download the email', async () => {
    renderPreview(<EmailView document={{ ...mail, canOpen: false }} />, { [message]: messageView() })

    await screen.findByRole('list', { name: 'Attachments' })
    expect(screen.queryByRole('link', { name: 'Download Schedule.xlsx' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Download Schedule.xlsx' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('button', { name: 'Save to Matter: Contract.pdf' }).hasAttribute('disabled')).toBe(true)
  })

  it('reads an attachment again when the email has a new version', async () => {
    function Versions() {
      const [version, setVersion] = useState('1.0')
      return (
        <>
          <button onClick={() => setVersion('1.1')}>Next Version</button>
          <EmailView document={{ ...mail, fileVersion: version }} />
        </>
      )
    }
    const { api } = renderPreview(<Versions />, { [message]: messageView(), [part]: () => pdfResponse() })
    fireEvent.click(await screen.findByRole('button', { name: 'Preview Contract.pdf' }))
    await screen.findByText('Page 1 of 3')
    expect(api.callsTo(part)).toHaveLength(1)

    // The same attachment, in the next version of the email, is not the bytes kept for the old one
    fireEvent.click(screen.getByRole('button', { name: 'Next Version' }))
    await waitFor(() => expect(api.callsTo(part)).toHaveLength(2))
  })

  it('passes axe', async () => {
    const { container } = renderPreview(<EmailView document={mail} />, { [message]: messageView() })
    await screen.findByTitle('Message Body')
    await expectNoAxeViolations(container)
  })
})

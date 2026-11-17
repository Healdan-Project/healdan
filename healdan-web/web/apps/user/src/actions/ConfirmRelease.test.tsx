// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { formatDateTime } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { document } from '../test/fakeApi'
import { ConfirmRelease } from './ConfirmRelease'

const priya = { id: 5, name: 'Priya Nair' }
const onComputer = (name: string | null, app: string) => ({
  kind: 'checkout' as const,
  by: priya,
  since: '2026-11-12T09:00:00Z',
  device: { id: `${name}-1`, name: name ?? 'Healdan Helper', app },
  heldOn: { name, app },
})
const macBook = (app: string) => onComputer('Healdan Helper on MacBook', app)

const laytime = document({ docNumber: 10405, name: 'Laytime Calculation – MV Northern Star.xlsx', lastModified: '2026-11-16T17:05:00Z', hold: macBook('Excel') })
const report = document({ docNumber: 10447, name: 'Report on Lease – Unit 4.odt', lastModified: '2026-11-14T11:30:00Z', hold: macBook('LibreOffice') })
const grant = document({ docNumber: 10414, name: 'Grant Agreement – Draft.docx', lastModified: '2026-11-12T15:48:00Z', hold: macBook('Word') })
const letter = document({ docNumber: 10431, name: 'Letter to Dunmere Pryce – Response to Claim.docx', hold: onComputer('Healdan Helper on iMac', 'Word') })
const capTable = document({ docNumber: 10420, name: 'Cap Table – Series A.xlsx', hold: { kind: 'checkout', by: priya, since: '2026-11-10T09:00:00Z', device: null, heldOn: null } })

function show(documents: ReturnType<typeof document>[], over: Partial<Parameters<typeof ConfirmRelease>[0]> = {}) {
  const props = { documents, onConfirm: vi.fn(), onCancel: vi.fn(), onShowDevices: vi.fn(), ...over }
  const { unmount } = render(<ConfirmRelease {...props} />)
  return { ...props, unmount }
}

/** The sentence that holds the Signed-in Devices button, whole */
const stolen = (dialog: HTMLElement) => within(dialog).getByRole('button', { name: 'Signed-in Devices' }).parentElement?.textContent

describe('ConfirmRelease', () => {
  it('warns about one computer', async () => {
    show([laytime, report])
    const dialog = await screen.findByRole('alertdialog', { name: 'Release 2 Documents from MacBook?' })
    expect(within(dialog).getByText("They're checked out to you on MacBook:")).toBeTruthy()
    for (const [one, app] of [[laytime, 'Excel'], [report, 'LibreOffice']] as const) {
      const item = within(dialog).getByText(one.name).closest('li')!
      expect(within(item).getByText(`# ${one.docNumber}`)).toBeTruthy()
      expect(within(item).getByText(app)).toBeTruthy()
      expect(within(item).getByText(`Saved ${formatDateTime(one.lastModified)}`)).toBeTruthy()
    }
    expect(within(dialog).getByText('# 10405')).toBeTruthy()
    expect(within(dialog).getByText("Changes on MacBook that haven't reached Healdan will be lost.")).toBeTruthy()
    expect(within(dialog).getByText("Once released, MacBook can't save to these documents again, and the work in its copies can't be carried on there.")).toBeTruthy()
    expect(within(dialog).getByText('If you get its copies back, upload each one as a new version of its document to rescue those changes.')).toBeTruthy()
    expect(stolen(dialog)).toBe('If MacBook was lost or stolen, also revoke its sign-in in Signed-in Devices.')
    expect(globalThis.document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(within(dialog).getByRole('button', { name: 'Release 2 Documents' })).toBeTruthy()
    await expectNoAxeViolations(dialog)
  })

  it('names one document', async () => {
    show([grant])
    const dialog = await screen.findByRole('alertdialog', { name: 'Release Grant Agreement – Draft.docx from MacBook?' })
    expect(within(dialog).getByText("It's checked out to you on MacBook:")).toBeTruthy()
    expect(within(dialog).getByText(/can't save to this document again, and the work in its copy can't/)).toBeTruthy()
    expect(within(dialog).getByText('If you get its copy back, upload it as a new version of its document to rescue those changes.')).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Release Document' })).toBeTruthy()
  })

  it('groups by computer and counts every document', async () => {
    show([letter, laytime, capTable, report])
    const dialog = await screen.findByRole('alertdialog', { name: 'Release 4 Documents from Other Computers?' })
    // In the selection's order
    expect(within(dialog).getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)).toEqual(['On iMac', 'On MacBook'])
    const names = (computer: string) => within(within(dialog).getByRole('list', { name: computer })).getAllByRole('listitem').map((item) => within(item).getByText(/\.(docx|xlsx|odt)$/).textContent)
    expect(names('On MacBook')).toEqual([laytime.name, report.name])
    expect(names('On iMac')).toEqual([letter.name])
    // The browser's check-out is released with them, without being listed
    expect(within(dialog).queryByText(capTable.name)).toBeNull()
    expect(within(dialog).queryByText(/checked out to you on/)).toBeNull()
    expect(within(dialog).getByText("Changes on those computers that haven't reached Healdan will be lost.")).toBeTruthy()
    expect(within(dialog).getByText("Once released, they can't save to these documents again, and the work in their copies can't be carried on there.")).toBeTruthy()
    expect(within(dialog).getByText('If you get their copies back, upload each one as a new version of its document to rescue those changes.')).toBeTruthy()
    expect(stolen(dialog)).toBe('If one of them was lost or stolen, also revoke its sign-in in Signed-in Devices.')
    expect(within(dialog).getByRole('button', { name: 'Release 4 Documents' })).toBeTruthy()
    await expectNoAxeViolations(dialog)
  })

  it('reads a computer with no name as Another Computer', async () => {
    show([document({ ...grant, hold: onComputer(null, 'Word') })])
    expect(await screen.findByRole('alertdialog', { name: 'Release Grant Agreement – Draft.docx from Another Computer?' })).toBeTruthy()
  })

  it('Esc and Cancel release nothing', async () => {
    const byEsc = show([laytime])
    await screen.findByRole('alertdialog')
    await userEvent.keyboard('{Escape}')
    expect(byEsc.onCancel).toHaveBeenCalled()
    expect(byEsc.onConfirm).not.toHaveBeenCalled()
    byEsc.unmount()

    const byCancel = show([laytime])
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(byCancel.onCancel).toHaveBeenCalled()
    expect(byCancel.onConfirm).not.toHaveBeenCalled()
    byCancel.unmount()

    // Enter on the focused Cancel is no release either
    const byEnter = show([laytime])
    await screen.findByRole('alertdialog')
    await userEvent.keyboard('{Enter}')
    expect(byEnter.onConfirm).not.toHaveBeenCalled()
  })

  it('releases with the red button', async () => {
    const props = show([laytime])
    await userEvent.click(await screen.findByRole('button', { name: 'Release Document' }))
    expect(props.onConfirm).toHaveBeenCalledOnce()
  })

  it('Signed-in Devices calls onShowDevices', async () => {
    const props = show([laytime])
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Signed-in Devices' }))
    expect(props.onShowDevices).toHaveBeenCalledOnce()
    expect(props.onCancel).not.toHaveBeenCalled()
    expect(props.onConfirm).not.toHaveBeenCalled()
  })
})

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { formatDateTime } from '@healdan/ui'
import { expectNoAxeViolations } from '../test/axe'
import { document } from '../test/fakeApi'
import type { ReleaseTarget } from './checkout'
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
/**
 * A document as ReleaseProvider hands it over: with its decision, the device that holds it, which is ReleaseProvider's to
 * make (Release.test.tsx)
 */
const target = (over: Parameters<typeof document>[0], elsewhere: string | undefined = over?.hold?.device?.id): ReleaseTarget => ({ ...document(over), elsewhere })

const laytime = target({ docNumber: 10405, name: 'Laytime Calculation – MV Northern Star.xlsx', lastModified: '2026-11-16T17:05:00Z', hold: macBook('Excel') })
const report = target({ docNumber: 10447, name: 'Report on Lease – Unit 4.odt', lastModified: '2026-11-14T11:30:00Z', hold: macBook('LibreOffice') })
const grant = target({ docNumber: 10414, name: 'Grant Agreement – Draft.docx', lastModified: '2026-11-12T15:48:00Z', hold: macBook('Word') })
const letter = target({ docNumber: 10431, name: 'Letter to Dunmere Pryce – Response to Claim.docx', hold: onComputer('Healdan Helper on iMac', 'Word') })
const capTable = target({ docNumber: 10420, name: 'Cap Table – Series A.xlsx', hold: { kind: 'checkout', by: priya, since: '2026-11-10T09:00:00Z', device: null, heldOn: null } }, undefined)

function show(documents: ReleaseTarget[], over: Partial<Parameters<typeof ConfirmRelease>[0]> = {}) {
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
    // A long list scrolls, so it takes the focus, by which the keyboard scrolls it
    const list = within(dialog).getByRole('list', { name: 'Documents on MacBook' })
    expect(list.tabIndex).toBe(0)
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
    // The red button first, as in the app's other dialogs, and Cancel has the focus
    const buttons = within(dialog).getAllByRole('button').filter((button) => !button.textContent?.includes('Signed-in Devices'))
    expect(buttons.map((button) => button.textContent)).toEqual(['Release 2 Documents', 'Cancel'])
    expect(globalThis.document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancel' }))
    await expectNoAxeViolations(dialog)
  })

  it('says what is lost as it opens', async () => {
    show([laytime, report])
    expect(await screen.findByRole('alertdialog', { name: 'Release 2 Documents from MacBook?', description: /^Changes on MacBook that haven't reached Healdan will be lost\./ })).toBeTruthy()
  })

  it('names one document', async () => {
    show([grant])
    const dialog = await screen.findByRole('alertdialog', { name: 'Release Grant Agreement – Draft.docx from MacBook?' })
    expect(within(dialog).getByText("It's checked out to you on MacBook:")).toBeTruthy()
    expect(within(dialog).getByText(/can't save to this document again, and the work in its copy can't/)).toBeTruthy()
    expect(within(dialog).getByText('If you get its copy back, upload it as a new version of its document to rescue those changes.')).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Release Document' })).toBeTruthy()
  })

  it("groups by computer; the title counts what's listed, the button every document", async () => {
    show([letter, laytime, capTable, report])
    const dialog = await screen.findByRole('alertdialog', { name: 'Release 3 Documents from Other Computers?' })
    // In the selection's order
    expect(within(dialog).getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)).toEqual(['On iMac', 'On MacBook'])
    const names = (computer: string) => within(within(dialog).getByRole('list', { name: computer })).getAllByRole('listitem').map((item) => within(item).getByText(/\.(docx|xlsx|odt)$/).textContent)
    expect(names('Documents on MacBook')).toEqual([laytime.name, report.name])
    expect(names('Documents on iMac')).toEqual([letter.name])
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

  it('keeps two computers of one name apart', async () => {
    // A new MacBook restored from the stolen one's backup keeps its name, but it's another device
    const onDevice = (id: string, app: string) => ({ ...macBook(app), device: { id, name: 'Healdan Helper on MacBook', app } })
    const stolenMac = { ...laytime, hold: onDevice('mac-old', 'Excel'), elsewhere: 'mac-old' }
    const newMac = { ...report, hold: onDevice('mac-new', 'LibreOffice'), elsewhere: 'mac-new' }
    show([stolenMac, newMac])
    const dialog = await screen.findByRole('alertdialog', { name: 'Release 2 Documents from Other Computers?' })
    expect(within(dialog).getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)).toEqual(['On MacBook', 'On MacBook'])
    const lists = within(dialog).getAllByRole('list', { name: 'Documents on MacBook' })
    expect(lists.map((list) => within(list).getAllByRole('listitem').map((item) => within(item).getByText(/\.(xlsx|odt)$/).textContent))).toEqual([[laytime.name], [report.name]])
    expect(within(dialog).queryByText(/checked out to you on/)).toBeNull()
    expect(within(dialog).getByText("Changes on those computers that haven't reached Healdan will be lost.")).toBeTruthy()
    expect(stolen(dialog)).toBe('If one of them was lost or stolen, also revoke its sign-in in Signed-in Devices.')
    expect(within(dialog).getByRole('button', { name: 'Release 2 Documents' })).toBeTruthy()
  })

  it('takes one device as one computer, whatever its name', async () => {
    // A check-out names the token its device last wrote it through, so after signing in again under a new name, one
    // device's check-outs can carry two
    const renamed = { ...report, hold: { ...macBook('LibreOffice'), heldOn: { name: 'Healdan Helper on MacBook Pro', app: 'LibreOffice' } } }
    show([laytime, renamed])
    const dialog = await screen.findByRole('alertdialog', { name: 'Release 2 Documents from MacBook?' })
    expect(within(within(dialog).getByRole('list', { name: 'Documents on MacBook' })).getAllByRole('listitem')).toHaveLength(2)
  })

  it("lists what it's told another computer holds, and nothing else", async () => {
    // The decision is ReleaseProvider's alone: a document it sends without elsewhere isn't listed, whatever its hold says
    show([laytime, { ...report, elsewhere: undefined }])
    // Named for what MacBook holds; the button counts both
    const dialog = await screen.findByRole('alertdialog', { name: 'Release Laytime Calculation – MV Northern Star.xlsx from MacBook?' })
    expect(within(dialog).getByRole('button', { name: 'Release 2 Documents' })).toBeTruthy()
    expect(within(dialog).getByText(laytime.name)).toBeTruthy()
    expect(within(dialog).queryByText(report.name)).toBeNull()
    expect(within(dialog).getByText("It's checked out to you on MacBook:")).toBeTruthy()
  })

  it('reads a computer with no name as Another Computer', async () => {
    show([{ ...grant, hold: onComputer(null, 'Word') }])
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

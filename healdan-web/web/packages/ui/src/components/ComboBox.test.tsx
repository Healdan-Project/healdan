// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { createPortal } from 'react-dom'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import '../tokens.css'
import { Icon } from '../icons'
import { ComboBox, type ComboBoxSection } from './ComboBox'

const sections: ComboBoxSection[] = [
  { id: 'matters', title: 'Matters', options: [{ id: 'm1', textValue: '1001.1 Charter Dispute', label: '1001.1 – Charter Dispute', meta: 'Saltmarsh' }] },
  { id: 'documents', title: 'Documents', options: [{ id: 'd1', textValue: 'Charter.docx', label: 'Charter.docx', meta: '# 10392' }] },
  { id: 'footer', title: 'Search', showTitle: false, options: [{ id: 'search', textValue: 'Search Documents for "ch"', label: 'Search Documents for "ch"' }] },
]

function Example({ onSelect = vi.fn(), onSubmit = vi.fn(), shown = sections }: { onSelect?: (id: string) => void; onSubmit?: (text: string) => void; shown?: ComboBoxSection[] }) {
  const [value, setValue] = useState('')
  return <ComboBox aria-label="Search" value={value} onValueChange={setValue} sections={shown} onSelect={onSelect} onSubmit={onSubmit} />
}

function type(text: string) {
  const box = screen.getByRole('combobox', { name: 'Search' })
  box.focus()
  fireEvent.change(box, { target: { value: text } })
  return box
}

describe('ComboBox', () => {
  it('offers its sections as it is typed in, each named', async () => {
    render(<Example />)
    type('ch')
    const list = await screen.findByRole('listbox')
    expect(within(list).getAllByRole('group').map((g) => g.getAttribute('aria-label') ?? g.textContent)).toEqual(expect.arrayContaining([expect.stringContaining('Matters'), expect.stringContaining('Documents')]))
    expect(within(list).getAllByRole('option').map((o) => o.textContent)).toEqual([
      '1001.1 – Charter DisputeSaltmarsh',
      'Charter.docx# 10392',
      'Search Documents for "ch"',
    ])
  })

  it('picks with the arrow keys and Enter, calling onSelect and not onSubmit', async () => {
    const onSelect = vi.fn()
    const onSubmit = vi.fn()
    render(<Example onSelect={onSelect} onSubmit={onSubmit} />)
    const box = type('ch')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'ArrowDown' })
    fireEvent.keyDown(box, { key: 'ArrowDown' })
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(onSelect).toHaveBeenCalledWith('d1')
    expect(onSubmit).not.toHaveBeenCalled()
  })

  it('draws a kind\'s icon in its colour, and in the fill\'s own on the option the arrow keys are on', async () => {
    const withIcons: ComboBoxSection[] = [
      {
        id: 'matters',
        title: 'Matters',
        options: [
          { id: 'm1', textValue: '1001.1', icon: <Icon name="briefcase" kind="matter" />, label: '1001.1' },
          { id: 'm2', textValue: '1001.2', icon: <Icon name="briefcase" kind="matter" />, label: '1001.2' },
        ],
      },
    ]
    render(<Example shown={withIcons} />)
    const box = type('10')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'ArrowDown' })
    const colour = (name: string) => getComputedStyle(screen.getByRole('option', { name }).querySelector('svg')!).color
    await waitFor(() => expect(screen.getByRole('option', { name: '1001.1' }).hasAttribute('data-focused')).toBe(true))
    expect(colour('1001.1')).toBe('rgb(255, 255, 255)')
    expect(colour('1001.2')).toBe('rgb(138, 90, 0)')
  })

  it('submits the text on Enter when nothing is picked, with or without options showing', async () => {
    const onSelect = vi.fn()
    const onSubmit = vi.fn()
    render(<Example onSelect={onSelect} onSubmit={onSubmit} />)
    const box = type('ch')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(onSubmit).toHaveBeenCalledWith('ch')
    expect(onSelect).not.toHaveBeenCalled()
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('closes after an option is picked, whatever the caller does with the text', async () => {
    render(<Example />)
    const box = type('ch')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'ArrowDown' })
    fireEvent.keyDown(box, { key: 'Enter' })
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
  })

  it('can pick the same option twice in a row', async () => {
    const onSelect = vi.fn()
    render(<Example onSelect={onSelect} />)
    for (let i = 0; i < 2; i++) {
      const box = type(`ch${i}`)
      await screen.findByRole('listbox')
      fireEvent.keyDown(box, { key: 'ArrowDown' })
      fireEvent.keyDown(box, { key: 'Enter' })
    }
    expect(onSelect.mock.calls).toEqual([['m1'], ['m1']])
  })

  it('shows no list when it has no options', () => {
    render(<Example shown={[]} />)
    type('zz')
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('closes on Escape, keeping what was typed', async () => {
    render(<Example />)
    const box = type('ch')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).toBeNull()
    expect((box as HTMLInputElement).value).toBe('ch')
  })

  it('passes axe, closed and open', async () => {
    const { container } = render(
      <main>
        <Example />
      </main>,
    )
    await expectNoAxeViolations(container)
    type('ch')
    await screen.findByRole('listbox')
    await expectNoAxeViolations(document.body)
  })

  // What carries aria-hidden, to say so when anything does
  const hiddenOnes = () => [...document.querySelectorAll('[aria-hidden="true"]')].map((element) => element.outerHTML.slice(0, 80))

  // A page as the apps' top bars have it: the box in a search landmark between links and buttons, and a main area, with a
  // link that comes in later (`late`)
  function Page({ shown = sections, late = false }: { shown?: ComboBoxSection[]; late?: boolean }) {
    return (
      <>
        <header>
          <a href="/app/">Home</a>
          <form role="search">
            <Example shown={shown} />
          </form>
          <button type="button">Help</button>
        </header>
        <main>
          <h1>Recent Documents</h1>
          <a href="/app/doc/10392">Charter.docx</a>
          {late && <a href="/app/doc/10393">Reply.docx</a>}
        </main>
      </>
    )
  }

  it('leaves the page around it to a screen reader while its list is open, the list right after the box', async () => {
    render(<Page />)
    type('ch')
    const list = await screen.findByRole('listbox')
    // Found by role only when nothing hides them (aria-hidden on them or around them)
    expect(screen.getByRole('link', { name: 'Home' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Help' })).toBeTruthy()
    expect(within(screen.getByRole('main')).getByRole('heading', { level: 1, name: 'Recent Documents' })).toBeTruthy()
    expect(hiddenOnes()).toEqual([])
    // In the search landmark, after the box in reading order
    const box = screen.getByRole('combobox', { name: 'Search' })
    expect(screen.getByRole('search').contains(list)).toBe(true)
    expect(box.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // The whole page, every rule but colour contrast (jsdom has no colours): nothing hidden holds a link (aria-hidden-focus),
    // the page keeps its main landmark and heading, and the list is inside a landmark (region). jsdom's page has no title
    // or language of its own, which the apps' index.html give theirs.
    const title = document.title
    const lang = document.documentElement.lang
    document.title = 'Recent Documents'
    document.documentElement.lang = 'en-US'
    try {
      await expectNoAxeViolations(document)
    } finally {
      document.title = title
      document.documentElement.lang = lang
    }
    // React Aria's mark for a layer it keeps shown is gone once it has looked
    expect(document.querySelector('[data-react-aria-top-layer]')).toBeNull()
  })

  it('leaves the page shown when the box is in an overlay of its own at <body>, as the Tray is', async () => {
    render(
      <>
        <main>
          <h1>Recent Documents</h1>
          <a href="/app/doc/10392">Charter.docx</a>
        </main>
        {createPortal(
          <div>
            <Example />
          </div>,
          document.body,
        )}
      </>,
    )
    type('ch')
    await screen.findByRole('listbox')
    expect(screen.getByRole('link', { name: 'Charter.docx' })).toBeTruthy()
    expect(hiddenOnes()).toEqual([])
  })

  it('leaves what comes into the page and the list once the list is open shown too', async () => {
    // At first only the search itself, then the matters and documents, as the User app's suggestions come
    const { rerender } = render(<Page shown={sections.filter((section) => section.id === 'footer')} />)
    type('ch')
    const list = await screen.findByRole('listbox')
    expect(within(list).getAllByRole('option')).toHaveLength(1)
    rerender(<Page shown={sections} late />)
    expect(await screen.findByRole('option', { name: /Charter Dispute/ })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Reply.docx' })).toBeTruthy()
    expect(hiddenOnes()).toEqual([])
  })

  // Where the layer lands in a real browser (over the whole window, the list under the field) is checked by the a11y specs
  // (e2e/helpers.ts's expectListUnder): jsdom has no layout.
  it('draws its list in a layer of its own after the box, fixed and taking no clicks, and draws no layer while the list is closed', async () => {
    render(
      <div style={{ position: 'relative', overflow: 'hidden' }}>
        <Example />
      </div>,
    )
    const box = type('ch')
    const list = await screen.findByRole('listbox')
    let layer = list.parentElement
    while (layer !== null && getComputedStyle(layer).position !== 'fixed') layer = layer.parentElement
    expect(layer).not.toBeNull()
    // The layer's last ancestor before the page is the caller's, not <body>: the list is right after the box
    expect(layer!.parentElement).not.toBe(document.body)
    expect(getComputedStyle(layer!).pointerEvents).toBe('none')
    expect(getComputedStyle(layer!).display).not.toBe('none')
    expect(getComputedStyle(list.closest('[data-trigger="ComboBox"]')!).pointerEvents).toBe('auto')
    fireEvent.keyDown(box, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    expect(layer!.childElementCount).toBe(0)
    expect(getComputedStyle(layer!).display).toBe('none')
  })

  it("keeps its list's scroller out of the Tab order, and the focus in the box when the list is pressed", async () => {
    const user = userEvent.setup()
    render(<Page />)
    const box = screen.getByRole('combobox', { name: 'Search' })
    await user.click(box)
    await user.keyboard('ch')
    const list = await screen.findByRole('listbox')
    // What scrolls when the options don't fit is the element between the popover and the list, which Firefox and Chromium
    // would otherwise stop Tab on, as nothing in it can take the focus; the popover itself doesn't scroll
    const scroller = list.parentElement!
    expect(getComputedStyle(scroller).overflow).toBe('auto')
    expect(scroller.tabIndex).toBe(-1)
    expect(getComputedStyle(list.closest('[data-trigger="ComboBox"]')!).overflow).not.toBe('auto')
    // A press on a section's heading, between options, leaves the focus in the box and the list open
    await user.click(within(list).getByText('Matters'))
    expect(document.activeElement).toBe(box)
    expect(screen.getByRole('listbox')).toBe(list)
  })

  it('still keeps the page shown when the list opens again, and leaves nothing hidden once it closes', async () => {
    render(<Page />)
    const box = type('ch')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).toBeNull()
    fireEvent.change(box, { target: { value: 'cha' } })
    await screen.findByRole('listbox')
    expect(hiddenOnes()).toEqual([])
    fireEvent.keyDown(box, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    expect(hiddenOnes()).toEqual([])
  })

  it('goes on to the control after it with Tab, closing the list on the way', async () => {
    const user = userEvent.setup()
    render(<Page />)
    await user.click(screen.getByRole('combobox', { name: 'Search' }))
    await user.keyboard('ch')
    await screen.findByRole('listbox')
    await user.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Help' }))
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
  })
})

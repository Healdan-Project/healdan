// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import axe from 'axe-core'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import '../tokens.css'
import { Button } from './Button'
import { Dialog } from './Dialog'
import { TextField } from './Fields'
import { PickField, type PickFieldOption } from './PickField'

const jamie = { id: '3', label: 'Jamie Clarke' }
const pat = { id: '7', label: 'Pat Example' }

function Example({ start = jamie, onChange = vi.fn(), required, error }: { start?: PickFieldOption | null; onChange?: (value: PickFieldOption | null) => void; required?: boolean; error?: string }) {
  const [value, setValue] = useState<PickFieldOption | null>(start)
  const [text, setText] = useState('')
  const options = text === '' ? [] : [pat, jamie].filter((one) => one.label.toLowerCase().includes(text.toLowerCase()))
  return (
    <>
      <PickField
        label="Author"
        required={required}
        error={error}
        value={value}
        options={options}
        onTextChange={setText}
        onChange={(next) => {
          setValue(next)
          onChange(next)
        }}
      />
      <button type="button">After</button>
    </>
  )
}

const box = () => screen.getByRole('combobox', { name: /Author/ }) as HTMLInputElement

describe('PickField', () => {
  it('shows what is picked, under its label', () => {
    render(<Example />)
    expect(box().value).toBe('Jamie Clarke')
  })

  it('offers what is picked first, then the options found, and picks one by keyboard', async () => {
    const onChange = vi.fn()
    render(<Example onChange={onChange} />)
    await userEvent.clear(box())
    await userEvent.type(box(), 'pa')
    const list = await screen.findByRole('listbox')
    expect(within(list).getAllByRole('option').map((one) => one.textContent)).toEqual(['Jamie Clarke', 'Pat Example'])
    await userEvent.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    expect(onChange).toHaveBeenLastCalledWith(pat)
    expect(box().value).toBe('Pat Example')
  })

  it('goes back to what was picked when left half typed, and picks nothing when left empty', async () => {
    const onChange = vi.fn()
    render(<Example onChange={onChange} />)
    await userEvent.clear(box())
    await userEvent.type(box(), 'p')
    await userEvent.tab()
    expect(box().value).toBe('Jamie Clarke')
    expect(onChange).not.toHaveBeenCalled()
    await userEvent.clear(box())
    await userEvent.tab()
    expect(box().value).toBe('')
    expect(onChange).toHaveBeenLastCalledWith(null)
  })

  it('offers nothing in an emptied box, and keeps what is picked when it is picked again from the list', async () => {
    const onChange = vi.fn()
    render(<Example onChange={onChange} />)
    await userEvent.clear(box())
    await userEvent.keyboard('{ArrowDown}')
    expect(screen.queryByRole('listbox')).toBeNull()

    await userEvent.type(box(), 'jam')
    await screen.findByRole('option', { name: 'Jamie Clarke' })
    await userEvent.keyboard('{ArrowDown}{Enter}')
    expect(box().value).toBe('Jamie Clarke')
    expect(onChange).not.toHaveBeenCalled()

    await userEvent.clear(box())
    await userEvent.type(box(), 'cla')
    await userEvent.click(await screen.findByRole('option', { name: 'Jamie Clarke' }))
    expect(box().value).toBe('Jamie Clarke')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('shows an option\'s icon and its secondary text beside its label, in the option\'s name, and only the label once picked', async () => {
    const onChange = vi.fn()
    function WithMeta() {
      const [value, setValue] = useState<PickFieldOption | null>(null)
      const options: PickFieldOption[] = [{ id: '7', label: 'Pat Example', meta: 'pexample', icon: 'briefcase' }]
      return (
        <PickField
          label="Author"
          value={value}
          options={options}
          onTextChange={() => {}}
          onChange={(next) => {
            setValue(next)
            onChange(next)
          }}
        />
      )
    }
    render(<WithMeta />)
    await userEvent.type(box(), 'pa')
    const option = await screen.findByRole('option', { name: 'Pat Example pexample' })
    // Smaller and muted, after the label (ComboBox.module.css)
    expect(within(option).getByText('pexample').className).toContain('meta')
    // The icon is decoration: the label and its secondary text name the option
    expect(option.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
    await expectNoAxeViolations(screen.getByRole('listbox'))
    await userEvent.keyboard('{ArrowDown}{Enter}')
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ id: '7' }))
    expect(box().value).toBe('Pat Example')
  })

  it('marks a required field and says what is wrong with it, and passes axe', async () => {
    const { container } = render(<Example required error="This needs a value." start={null} />)
    expect(box().getAttribute('aria-required')).toBe('true')
    expect(box().getAttribute('aria-invalid')).toBe('true')
    expect(screen.getByText('This needs a value.')).toBeTruthy()
    await expectNoAxeViolations(container)
  })

  // What carries aria-hidden, to say so when anything does
  const hiddenOnes = (inside: ParentNode = document) => [...inside.querySelectorAll('[aria-hidden="true"]')].map((element) => element.outerHTML.slice(0, 80))

  // Every rule but colour contrast (jsdom has no colours), over the whole page or one part of it. jsdom's page has no title
  // or language of its own, which the apps' index.html give theirs.
  async function axeViolations(context: Document | Element) {
    const { title, lang } = { title: document.title, lang: document.documentElement.lang }
    document.title = 'Properties'
    document.documentElement.lang = 'en-US'
    try {
      const result = await axe.run(context, { rules: { 'color-contrast': { enabled: false } } })
      return result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html).join(' | ')}`)
    } finally {
      document.title = title
      document.documentElement.lang = lang
    }
  }

  async function openList() {
    await userEvent.clear(box())
    await userEvent.type(box(), 'pa')
    return screen.findByRole('listbox')
  }

  // A page as the properties form has it: links and buttons before and after the field, in the page's main area
  function Page() {
    return (
      <>
        <header>
          <a href="/app/">Home</a>
        </header>
        <main>
          <h1>Properties</h1>
          <form aria-label="Properties">
            <Example />
          </form>
          <a href="/app/doc/10392">Charter.docx</a>
        </main>
      </>
    )
  }

  it('leaves the page around it to a screen reader while its list is open, the list right after the box', async () => {
    render(<Page />)
    const list = await openList()
    // Found by role only when nothing hides them (aria-hidden on them or around them)
    expect(screen.getByRole('link', { name: 'Home' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'After' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Charter.docx' })).toBeTruthy()
    expect(hiddenOnes()).toEqual([])
    // In the form, after the box in reading order
    expect(screen.getByRole('form', { name: 'Properties' }).contains(list)).toBe(true)
    expect(box().compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // The whole page: nothing hidden holds a link (aria-hidden-focus), and the list is inside a landmark (region)
    expect(await axeViolations(document)).toEqual([])
    // React Aria's mark for a layer it keeps shown is gone once it has looked
    expect(document.querySelector('[data-react-aria-top-layer]')).toBeNull()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    expect(hiddenOnes()).toEqual([])
  })

  // The filing form's dialog: the field among others, over a page the dialog hides
  function InDialog({ onOpenChange = vi.fn() }: { onOpenChange?: (open: boolean) => void }) {
    const [title, setTitle] = useState('')
    return (
      <>
        <main>
          <h1>Documents</h1>
          <button type="button">Upload</button>
        </main>
        <Dialog title="File Documents" isOpen onOpenChange={onOpenChange}>
          {(close) => (
            <>
              <TextField label="Title" value={title} onChange={setTitle} />
              <Example />
              <Button onPress={close}>Cancel</Button>
            </>
          )}
        </Dialog>
      </>
    )
  }

  it('in a dialog, leaves the dialog\'s other fields shown while its list is open, and draws the list in the dialog', async () => {
    render(<InDialog />)
    const dialog = screen.getByRole('dialog', { name: 'File Documents' })
    const list = await openList()
    expect(within(dialog).getByRole('textbox', { name: 'Title' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'After' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeTruthy()
    // Nothing in the dialog is hidden; only the page behind it is, by the dialog (jsdom has no inert, which a browser's
    // dialog uses instead)
    const overlay = dialog.closest('body > *')!
    expect(hiddenOnes(overlay)).toEqual([])
    expect(dialog.contains(list)).toBe(true)
    expect(await axeViolations(overlay)).toEqual([])
    expect(document.querySelector('[data-react-aria-top-layer]')).toBeNull()
  })

  it('in a dialog, closes its list on Escape and leaves the dialog open, and the next Escape closes the dialog', async () => {
    const onOpenChange = vi.fn()
    render(<InDialog onOpenChange={onOpenChange} />)
    await openList()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    expect(onOpenChange).not.toHaveBeenCalled()
    await userEvent.keyboard('{Escape}')
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('in a dialog, goes on to the next control with Tab, closing its list, and Tab stays in the dialog', async () => {
    render(<InDialog />)
    await openList()
    await userEvent.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'After' }))
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    await userEvent.tab()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cancel' }))
    await userEvent.tab()
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'Title' }))
  })

  it('in a dialog, closes its list on a click outside the dialog and leaves the dialog open, and the next one closes the dialog', async () => {
    const onOpenChange = vi.fn()
    render(<InDialog onOpenChange={onOpenChange} />)
    await openList()
    // The dialog's scrim (Dialog.module.css), outside the dialog
    const scrim = document.querySelector<HTMLElement>('.overlay')!
    await userEvent.click(scrim)
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    expect(onOpenChange).not.toHaveBeenCalled()
    await userEvent.click(scrim)
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })
})

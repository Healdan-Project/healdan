// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations, expectNoPageAxeViolations, hiddenOnes } from '../test/axe'
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

  // Options the caller finds later, as UserField's come from the server once the typing pauses: none at first, then
  // `found`, which it keeps whatever is typed or picked after, so only PickField decides whether they open the list
  function Later({ found }: { found: PickFieldOption[] }) {
    const [value, setValue] = useState<PickFieldOption | null>(null)
    return (
      <>
        <PickField label="Author" value={value} options={found} onTextChange={() => undefined} onChange={setValue} />
        <button type="button">After</button>
      </>
    )
  }

  it('opens its list when options come after the typing has stopped, and not again once one is picked', async () => {
    const { rerender } = render(<Later found={[]} />)
    await userEvent.type(box(), 'pa')
    expect(screen.queryByRole('listbox')).toBeNull()
    // The answer comes with no key pressed
    rerender(<Later found={[pat, jamie]} />)
    const list = await screen.findByRole('listbox')
    expect(within(list).getAllByRole('option').map((one) => one.textContent)).toEqual(['Pat Example', 'Jamie Clarke'])
    await userEvent.keyboard('{ArrowDown}{Enter}')
    expect(box().value).toBe('Pat Example')
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    // Answers that go and come again while the box holds the picked name, not typing, don't open it again
    rerender(<Later found={[]} />)
    rerender(<Later found={[pat]} />)
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(box()).toBe(document.activeElement)
  })

  it('leaves its list closed when options come after it was left, closed with Escape or emptied', async () => {
    const { rerender } = render(<Later found={[]} />)
    await userEvent.type(box(), 'pa')
    await userEvent.tab()
    rerender(<Later found={[pat]} />)
    expect(screen.queryByRole('listbox')).toBeNull()
    rerender(<Later found={[]} />)
    await userEvent.type(box(), 'pa')
    await userEvent.keyboard('{Escape}')
    rerender(<Later found={[pat]} />)
    expect(screen.queryByRole('listbox')).toBeNull()
    // Typed away to nothing, with the box still focused: an emptied box offers nothing
    rerender(<Later found={[]} />)
    await userEvent.type(box(), 'pa{Backspace}{Backspace}')
    rerender(<Later found={[pat]} />)
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(box()).toBe(document.activeElement)
  })

  // A form round the field that closes on Esc, as PropertiesForm does, unless something inside took it (defaultPrevented)
  function InForm({ onEscape }: { onEscape: () => void }) {
    return (
      <div
        onKeyDown={(event) => {
          if (event.key === 'Escape' && !event.defaultPrevented) onEscape()
        }}
      >
        <Example />
      </div>
    )
  }

  it('takes the Esc that closes its list, with someone picked, so a form round it that closes on Esc stays open', async () => {
    const onEscape = vi.fn()
    render(<InForm onEscape={onEscape} />)
    await openList()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
    expect(box().value).toBe('Jamie Clarke')
    expect(onEscape).not.toHaveBeenCalled()
    // With the list closed, Esc goes on to the form
    await userEvent.keyboard('{Escape}')
    expect(onEscape).toHaveBeenCalledTimes(1)
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

  it("draws its list right after the field, as ComboBox's is, leaving the page around it to a screen reader", async () => {
    render(
      <>
        <header>
          <a href="/app/">Home</a>
        </header>
        <main>
          <Example />
        </main>
      </>,
    )
    await userEvent.clear(box())
    await userEvent.type(box(), 'pa')
    const list = await screen.findByRole('listbox')
    expect(screen.getByRole('main').contains(list)).toBe(true)
    expect(box().compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // Found by role only when nothing hides them
    expect(screen.getByRole('link', { name: 'Home' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'After' })).toBeTruthy()
    expect([...document.querySelectorAll('[aria-hidden="true"]')].filter((element) => !list.contains(element))).toEqual([])
  })

  it('marks a required field and says what is wrong with it, and passes axe', async () => {
    const { container } = render(<Example required error="This needs a value." start={null} />)
    expect(box().getAttribute('aria-required')).toBe('true')
    expect(box().getAttribute('aria-invalid')).toBe('true')
    expect(screen.getByText('This needs a value.')).toBeTruthy()
    await expectNoAxeViolations(container)
  })

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
    // The whole page, every rule but colour contrast (jsdom has no colours): nothing hidden holds a link (aria-hidden-focus),
    // and the list is inside a landmark (region)
    await expectNoPageAxeViolations(document, 'Properties')
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
    await expectNoPageAxeViolations(overlay, 'File Documents')
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

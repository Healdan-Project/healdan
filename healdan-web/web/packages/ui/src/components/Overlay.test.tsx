// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { Button } from './Button'
import { Overlay } from './Overlay'

interface ExampleProps {
  onPrevious?: () => void
  onNext?: () => void
}

function Example({ onPrevious, onNext }: ExampleProps) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button onPress={() => setOpen(true)}>Show</Button>
      <Overlay label="Quick Look: Letter.pdf" isOpen={open} onClose={() => setOpen(false)} onPrevious={onPrevious} onNext={onNext} heading={<span>Letter.pdf</span>} actions={<Button>Open</Button>}>
        <p>The page</p>
      </Overlay>
    </>
  )
}

function show() {
  const trigger = screen.getByRole('button', { name: 'Show' })
  trigger.focus()
  fireEvent.keyDown(trigger, { key: 'Enter' })
  fireEvent.keyUp(trigger, { key: 'Enter' })
  return trigger
}

describe('Overlay', () => {
  it('opens as a modal dialog with the name it is given, its heading, actions and content', async () => {
    render(<Example />)
    show()
    const dialog = await screen.findByRole('dialog', { name: 'Quick Look: Letter.pdf' })
    expect(dialog.contains(document.activeElement)).toBe(true)
    expect(dialog.textContent).toContain('Letter.pdf')
    expect(dialog.textContent).toContain('The page')
    expect(screen.getByRole('button', { name: 'Open' })).not.toBeNull()
  })

  it('has Previous and Next buttons that disable at the ends of the list', async () => {
    const onNext = vi.fn()
    render(<Example onNext={onNext} />)
    show()
    await screen.findByRole('dialog')
    expect((screen.getByRole('button', { name: 'Previous Document' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Next Document' }))
    expect(onNext).toHaveBeenCalledTimes(1)
  })

  it('walks the list with the arrow keys', async () => {
    const onPrevious = vi.fn()
    const onNext = vi.fn()
    render(<Example onPrevious={onPrevious} onNext={onNext} />)
    show()
    const dialog = await screen.findByRole('dialog')
    fireEvent.keyDown(dialog, { key: 'ArrowRight' })
    fireEvent.keyDown(dialog, { key: 'ArrowDown' })
    fireEvent.keyDown(dialog, { key: 'ArrowLeft' })
    fireEvent.keyDown(dialog, { key: 'ArrowUp' })
    expect(onNext).toHaveBeenCalledTimes(2)
    expect(onPrevious).toHaveBeenCalledTimes(2)
  })

  it('leaves the arrow keys to a field, and to a key held with a modifier', async () => {
    const onNext = vi.fn()
    render(
      <Overlay label="Q" isOpen onClose={() => {}} onNext={onNext}>
        <input aria-label="Page" />
      </Overlay>,
    )
    const field = await screen.findByLabelText('Page')
    fireEvent.keyDown(field, { key: 'ArrowRight' })
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'ArrowRight', metaKey: true })
    expect(onNext).not.toHaveBeenCalled()
  })

  it('leaves the arrow keys to a scrollable page, which they scroll, and still takes Space and Esc there', async () => {
    const onNext = vi.fn()
    const onClose = vi.fn()
    render(
      <Overlay label="Q" isOpen onClose={onClose} onNext={onNext}>
        <div data-scrolls tabIndex={0} aria-label="The page" role="region">
          <span>page</span>
        </div>
      </Overlay>,
    )
    const page = await screen.findByRole('region', { name: 'The page' })
    for (const key of ['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp']) fireEvent.keyDown(page, { key })
    expect(onNext).not.toHaveBeenCalled()
    fireEvent.keyDown(page, { key: ' ' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('ignores Space that is held down, so it does not toggle Quick Look', async () => {
    const onClose = vi.fn()
    render(<Overlay label="Q" isOpen onClose={onClose}>x</Overlay>)
    const dialog = await screen.findByRole('dialog')
    fireEvent.keyDown(dialog, { key: ' ', repeat: true })
    expect(onClose).not.toHaveBeenCalled()
    fireEvent.keyDown(dialog, { key: ' ' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('closes on Escape and on Space, and returns focus', async () => {
    render(<Example />)
    const trigger = show()
    const dialog = await screen.findByRole('dialog')
    fireEvent.keyDown(dialog, { key: 'Escape' })
    fireEvent.keyUp(dialog, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(document.activeElement).toBe(trigger))

    show()
    fireEvent.keyDown(await screen.findByRole('dialog'), { key: ' ' })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it("leaves Space to the button that has it, which presses it", async () => {
    render(<Example />)
    show()
    await screen.findByRole('dialog')
    fireEvent.keyDown(screen.getByRole('button', { name: 'Open' }), { key: ' ' })
    expect(screen.getByRole('dialog')).not.toBeNull()
  })

  it('closes from its Close button', async () => {
    render(<Example />)
    show()
    fireEvent.click(await screen.findByRole('button', { name: 'Close Quick Look' }))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('passes axe', async () => {
    render(<Example onNext={() => {}} />)
    show()
    await screen.findByRole('dialog')
    await expectNoAxeViolations(document.body)
  })
})

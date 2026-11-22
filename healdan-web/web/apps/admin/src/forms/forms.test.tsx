// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it } from 'vitest'
import { MessagesProvider } from '@healdan/shell'
import { problemLookup } from '../strings'
import { expectNoAxeViolations } from '../test/axe'
import { problem } from '../test/fakeApi'
import { Problems } from './Problems'
import problemStyles from './Problems.module.css'
import { Refusal } from './Refusal'

function renderWith(node: ReactNode) {
  return render(<MessagesProvider lookup={problemLookup}>{node}</MessagesProvider>)
}

describe('A refusal', () => {
  it('says nothing while nothing was refused', () => {
    const { container } = renderWith(<Refusal failure={undefined} />)
    expect(container.textContent).toBe('')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it("says a rule's refusal in the rule's own sentence, drawn as text, as an alert", async () => {
    const { container } = renderWith(<Refusal failure={problem(409, 'refused', '<b>admin</b> is never disabled.')} />)
    const alert = screen.getByRole('alert')
    expect(alert.textContent).toBe('<b>admin</b> is never disabled.')
    expect(alert.querySelector('b')).toBeNull()
    await expectNoAxeViolations(container)
  })

  it("says any other problem in the catalogue's words, never the server's: the Admin app's own, then the shared", () => {
    renderWith(
      <>
        <Refusal failure={problem(500, 'server-error')} />
        <Refusal failure={problem(503, 'storage-unavailable')} />
      </>,
    )
    expect(screen.getAllByRole('alert').map((one) => one.textContent)).toEqual(['Something went wrong.', "This server doesn't have Healdan's storage module."])
  })

  it('says a sentence it is given, and nothing for none', () => {
    const { container } = renderWith(
      <>
        <Refusal>{"Couldn't copy it."}</Refusal>
        <Refusal>{undefined}</Refusal>
      </>,
    )
    expect(screen.getAllByRole('alert').map((one) => one.textContent)).toEqual(["Couldn't copy it."])
    expect(container.querySelectorAll('p')).toHaveLength(1)
  })
})

describe('Problems', () => {
  it('lists each problem, drawn as text, after the line that says what found them', async () => {
    const { container } = renderWith(<Problems lead="Test found 2 problems:" problems={['The bucket <i>x</i> is missing.', 'The key is wrong.']} />)
    expect(screen.getByText('Test found 2 problems:')).toBeTruthy()
    const items = within(screen.getByRole('list')).getAllByRole('listitem')
    expect(items.map((item) => item.textContent)).toEqual(['The bucket <i>x</i> is missing.', 'The key is wrong.'])
    // The warning sign is decoration
    expect(items[0]?.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true')
    expect(container.querySelector('i')).toBeNull()
    await expectNoAxeViolations(container)
  })

  it('puts what can put a problem right under its sentence, each in a box of its own when framed', async () => {
    const { container } = renderWith(
      <Problems framed problems={[{ key: 'p2', sentence: "Partners can't read the Store.", action: <button type="button">Let Partners Read the Store</button> }]} />,
    )
    const item = within(screen.getByRole('list')).getByRole('listitem')
    expect(within(item).getByRole('button', { name: 'Let Partners Read the Store' })).toBeTruthy()
    expect(item.classList.contains(problemStyles.framed!)).toBe(true)
    expect(item.hasAttribute('data-framed')).toBe(false)
    await expectNoAxeViolations(container)
  })

  it('gives a sentence the id it is given, so a greyed button can be described by it', () => {
    renderWith(<Problems problems={[{ key: 'disable', id: 'why-disable', sentence: "The admin account can't be disabled." }, 'The key is wrong.']} />)
    expect(document.getElementById('why-disable')?.textContent).toBe("The admin account can't be disabled.")
    expect(within(screen.getByRole('list')).getAllByRole('listitem')[1]?.querySelector('[id]')).toBeNull()
  })

  it('draws nothing when there is no problem', () => {
    const { container } = renderWith(<Problems lead="Test found no problem:" problems={[]} />)
    expect(container.textContent).toBe('')
  })
})

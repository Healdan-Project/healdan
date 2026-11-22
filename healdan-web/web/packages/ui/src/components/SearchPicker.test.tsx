// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import '../tokens.css'
import { Highlight, SEARCH_DELAY_MS, SearchPicker } from './SearchPicker'
import pickerCss from './SearchPicker.module.css?raw'

interface Matter {
  id: number
  number: string
  name: string
  client: string
}

const charter: Matter = { id: 11, number: '1001.1', name: 'Charter Dispute', client: 'Saltmarsh Shipping Ltd' }
const charity: Matter = { id: 43, number: '1004.3', name: 'Charity Registration', client: 'Marsh Lane Community Trust' }
const lease: Matter = { id: 121, number: '1012.1', name: 'Lease of Unit 4', client: 'Charnwood Holdings Ltd' }
const series: Matter = { id: 31, number: '1003.1', name: 'Series A Investment', client: 'Kestrel Software Ltd' }

/** A search whose answers the test gives, one for each time it is asked */
function controlled() {
  const asked: { q: string; answer: (items: Matter[]) => void; fail: () => void }[] = []
  const search = vi.fn(
    (q: string) =>
      new Promise<Matter[]>((resolve, reject) => {
        asked.push({ q, answer: resolve, fail: () => reject(new Error('down')) })
      }),
  )
  return { search, asked }
}

function Example({
  search,
  onPick = () => {},
  selectedId,
  initial = [series, charter],
  leaveOut,
}: {
  search: (q: string) => Promise<Matter[]>
  onPick?: (matter: Matter) => void
  selectedId?: string
  initial?: Matter[]
  leaveOut?: (matter: Matter) => boolean
}) {
  return (
    <SearchPicker
      label="Find a Matter"
      placeholder="Number, name or client"
      hint="Search by matter number, name or client."
      initialHeading="Recent Matters"
      initial={initial}
      search={search}
      id={(matter) => String(matter.id)}
      render={(matter, query) => (
        <span>
          <Highlight text={`${matter.number} ${matter.name}`} query={query} />{' '}
          <small>
            <Highlight text={matter.client} query={query} />
          </small>
        </span>
      )}
      onPick={onPick}
      selectedId={selectedId}
      leaveOut={leaveOut}
    />
  )
}

// A text box that names the row the arrow keys are on (aria-activedescendant), as React Aria's Autocomplete makes it
const field = () => screen.getByRole('textbox', { name: 'Find a Matter' })
const names = (list: HTMLElement) => within(list).getAllByRole('option').map((option) => option.textContent)

afterEach(() => {
  vi.useRealTimers()
})

describe('SearchPicker', () => {
  it('shows its heading and the starting rows while nothing is typed, the field named and described', async () => {
    const { search } = controlled()
    const { container } = render(<Example search={search} />)
    expect(field().getAttribute('placeholder')).toBe('Number, name or client')
    const description = document.getElementById(field().getAttribute('aria-describedby') ?? '')
    expect(description?.textContent).toBe('Search by matter number, name or client.')
    expect(screen.getByRole('heading', { name: 'Recent Matters' })).toBeTruthy()
    const list = screen.getByRole('listbox', { name: 'Recent Matters' })
    expect(names(list)).toEqual(['1003.1 Series A Investment Kestrel Software Ltd', '1001.1 Charter Dispute Saltmarsh Shipping Ltd'])
    // Nothing typed, nothing marked
    expect(container.querySelector('mark')).toBeNull()
    expect(search).not.toHaveBeenCalled()
    await expectNoAxeViolations(container)
  })

  it('hands its field to the caller, which can move the focus to it', () => {
    const { search } = controlled()
    const inputRef = createRef<HTMLInputElement>()
    render(
      <SearchPicker<Matter>
        label="Find a Matter"
        placeholder="Number, name or client"
        initialHeading="Recent Matters"
        initial={[]}
        search={search}
        id={(matter) => String(matter.id)}
        render={(matter) => matter.name}
        onPick={() => {}}
        inputRef={inputRef}
      />,
    )
    expect(inputRef.current).toBe(field())
    act(() => inputRef.current?.focus())
    expect(document.activeElement).toBe(field())
  })

  it('shows no heading or list when there are no starting rows', () => {
    const { search } = controlled()
    render(<Example search={search} initial={[]} />)
    expect(screen.queryByRole('heading')).toBeNull()
    expect(screen.queryByRole('listbox')).toBeNull()
  })

  it('asks once the typing pauses, for what was typed then, and not for each key', async () => {
    // The clock is the test's, so the keys come as change events (user-event waits on the clock itself between them)
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const { search, asked } = controlled()
    render(<Example search={search} />)
    act(() => field().focus())
    // A key every 100 ms, quicker than the pause the search waits for
    for (const typed of ['c', 'ch', 'cha', 'char']) {
      fireEvent.change(field(), { target: { value: typed } })
      await act(() => vi.advanceTimersByTimeAsync(100))
    }
    expect(search).not.toHaveBeenCalled()
    await act(() => vi.advanceTimersByTimeAsync(SEARCH_DELAY_MS - 100 - 1))
    expect(search).not.toHaveBeenCalled()
    await act(() => vi.advanceTimersByTimeAsync(1))
    expect(search).toHaveBeenCalledExactlyOnceWith('char')

    await act(async () => asked[0]!.answer([charter, charity, lease]))
    const list = screen.getByRole('listbox', { name: '3 Results' })
    expect(names(list)).toEqual([
      '1001.1 Charter Dispute Saltmarsh Shipping Ltd',
      '1004.3 Charity Registration Marsh Lane Community Trust',
      '1012.1 Lease of Unit 4 Charnwood Holdings Ltd',
    ])
    // The words typed are marked where they match, in the name and the client alike
    expect([...list.querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual(['Char', 'Char', 'Char'])
  })

  it('asks for nothing until two characters are typed, and goes back to the starting rows when the field is emptied', async () => {
    const user = userEvent.setup()
    const { search, asked } = controlled()
    render(<Example search={search} />)
    await user.type(field(), 'c')
    await new Promise((resolve) => setTimeout(resolve, SEARCH_DELAY_MS * 2))
    expect(search).not.toHaveBeenCalled()
    expect(screen.getByRole('listbox', { name: 'Recent Matters' })).toBeTruthy()

    await user.type(field(), 'h')
    await waitFor(() => expect(search).toHaveBeenCalledWith('ch'))
    await act(async () => asked[0]!.answer([charter]))
    expect(await screen.findByRole('listbox', { name: '1 Result' })).toBeTruthy()

    await user.clear(field())
    expect(screen.getByRole('listbox', { name: 'Recent Matters' })).toBeTruthy()
    expect(search).toHaveBeenCalledOnce()
  })

  it('leaves out the rows its caller says to, among the starting rows and what is found, as they change', async () => {
    const user = userEvent.setup()
    const { search, asked } = controlled()
    const { rerender } = render(<Example search={search} initial={[series, charter, charity]} leaveOut={(matter) => matter === charter} />)
    expect(names(screen.getByRole('listbox', { name: 'Recent Matters' }))).toEqual([
      '1003.1 Series A Investment Kestrel Software Ltd',
      '1004.3 Charity Registration Marsh Lane Community Trust',
    ])
    await user.type(field(), 'char')
    await waitFor(() => expect(search).toHaveBeenCalledWith('char'))
    await act(async () => asked[0]!.answer([charter, charity, lease]))
    // The count is of the rows it shows
    expect(names(await screen.findByRole('listbox', { name: '2 Results' }))).toEqual([
      '1004.3 Charity Registration Marsh Lane Community Trust',
      '1012.1 Lease of Unit 4 Charnwood Holdings Ltd',
    ])
    // A row its caller then leaves out goes from the answer it already has, without asking again
    rerender(<Example search={search} initial={[series, charter, charity]} leaveOut={(matter) => matter === charter || matter === charity} />)
    expect(names(screen.getByRole('listbox', { name: '1 Result' }))).toEqual(['1012.1 Lease of Unit 4 Charnwood Holdings Ltd'])
    expect(search).toHaveBeenCalledOnce()
  })

  it('picks a row with the arrow keys and Enter, from the starting rows and from what is found', async () => {
    const user = userEvent.setup()
    const onPick = vi.fn()
    const { search, asked } = controlled()
    render(<Example search={search} onPick={onPick} />)
    await user.click(field())
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    expect(onPick).toHaveBeenLastCalledWith(charter)

    await user.type(field(), 'char')
    await waitFor(() => expect(search).toHaveBeenCalledWith('char'))
    await act(async () => asked[0]!.answer([charter, charity, lease]))
    await screen.findByRole('listbox', { name: '3 Results' })
    // The focus stays in the field, and the arrow keys move through the rows
    await user.keyboard('{ArrowDown}{ArrowDown}')
    expect(document.activeElement).toBe(field())
    const active = document.getElementById(field().getAttribute('aria-activedescendant') ?? '')
    expect(active?.textContent).toContain('Charity Registration')
    await user.keyboard('{Enter}')
    expect(onPick).toHaveBeenLastCalledWith(charity)
    expect(onPick).toHaveBeenCalledTimes(2)
  })

  it('picks a row that is clicked', async () => {
    const user = userEvent.setup()
    const onPick = vi.fn()
    const { search } = controlled()
    render(<Example search={search} onPick={onPick} />)
    await user.click(screen.getByRole('option', { name: /Series A Investment/ }))
    expect(onPick).toHaveBeenCalledExactlyOnceWith(series)
  })

  it('ignores an answer to an older question, whenever it comes', async () => {
    const user = userEvent.setup()
    const { search, asked } = controlled()
    render(<Example search={search} />)
    await user.type(field(), 'char')
    await waitFor(() => expect(search).toHaveBeenCalledWith('char'))
    await user.type(field(), 'ter')
    await waitFor(() => expect(search).toHaveBeenLastCalledWith('charter'))

    // The older answer, before the newer one: nothing is shown for it
    await act(async () => asked[0]!.answer([charity, lease]))
    expect(screen.queryByRole('option', { name: /Charity Registration/ })).toBeNull()
    await act(async () => asked[1]!.answer([charter]))
    expect(names(screen.getByRole('listbox', { name: '1 Result' }))).toEqual(['1001.1 Charter Dispute Saltmarsh Shipping Ltd'])
  })

  it('ignores an older answer that comes after the newer one', async () => {
    const user = userEvent.setup()
    const { search, asked } = controlled()
    render(<Example search={search} />)
    await user.type(field(), 'char')
    await waitFor(() => expect(search).toHaveBeenCalledWith('char'))
    await user.type(field(), 'ter')
    await waitFor(() => expect(search).toHaveBeenLastCalledWith('charter'))
    await act(async () => asked[1]!.answer([charter]))
    await act(async () => asked[0]!.answer([charity, lease]))
    expect(names(screen.getByRole('listbox', { name: '1 Result' }))).toEqual(['1001.1 Charter Dispute Saltmarsh Shipping Ltd'])
  })

  it('says so when nothing is found, and when the search fails', async () => {
    const user = userEvent.setup()
    const { search, asked } = controlled()
    const { container } = render(<Example search={search} />)
    const status = screen.getByRole('status')
    expect(status.textContent).toBe('')
    await user.type(field(), 'zz')
    await waitFor(() => expect(search).toHaveBeenCalledWith('zz'))
    await act(async () => asked[0]!.answer([]))
    expect(status.textContent).toBe('Nothing found.')
    expect(screen.queryByRole('listbox')).toBeNull()
    await expectNoAxeViolations(container)

    await user.type(field(), 'z')
    await waitFor(() => expect(search).toHaveBeenCalledWith('zzz'))
    await act(async () => asked[1]!.fail())
    expect(status.textContent).toBe('Something went wrong.')
  })

  it('makes the box its rows scroll in a stop of its own, named by its heading, while they need scrolling, and only then', async () => {
    const user = userEvent.setup()
    // jsdom has no ResizeObserver, and lays nothing out: the box is given its heights, and its observers are called back by
    // hand, as a browser calls them when the box changes size (its height follows the window's)
    const observers: (() => void)[] = []
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: () => void) {
          observers.push(callback)
        }
        observe() {}
        disconnect() {}
      },
    )
    try {
      const { search } = controlled()
      // In a landmark, as on a page or in a dialog, so axe looks at the box as it is there
      render(
        <main>
          <Example search={search} initial={[series, charter, charity, lease]} />
          <button type="button">After</button>
        </main>,
      )
      const after = screen.getByRole('button', { name: 'After' })
      const box = screen.getByRole('listbox', { name: 'Recent Matters' }).parentElement!
      const lay = (holds: number) => {
        Object.defineProperty(box, 'clientHeight', { configurable: true, value: 200 })
        Object.defineProperty(box, 'scrollHeight', { configurable: true, value: holds })
        act(() => observers.forEach((observer) => observer()))
      }

      // Every row fits: Tab goes from the field to what follows, as the arrow keys in the field reach every row
      lay(200)
      expect(screen.queryByRole('region')).toBeNull()
      await user.click(field())
      await user.tab()
      expect(document.activeElement).toBe(after)

      // More rows than the box shows: Tab stops at the box, which the arrow keys then scroll, then goes on
      lay(400)
      expect(box.getAttribute('tabindex')).toBe('0')
      // A stop is named, as a region, by the heading over its rows
      expect(screen.getByRole('region', { name: 'Recent Matters' })).toBe(box)
      expect(document.getElementById(box.getAttribute('aria-labelledby') ?? '')?.tagName).toBe('H3')
      await expectNoAxeViolations(document.body)
      await user.click(field())
      await user.tab()
      expect(document.activeElement).toBe(box)
      await user.tab()
      expect(document.activeElement).toBe(after)

      // The window grew and they fit again: no stop
      lay(200)
      expect(box.hasAttribute('tabindex')).toBe(false)
      expect(box.hasAttribute('role')).toBe(false)
      expect(box.hasAttribute('aria-labelledby')).toBe(false)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('draws the focus on the box its rows scroll in, with the shared outline', () => {
    const css = pickerCss.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(css).toMatch(/\.scroll:focus-visible\s*\{(?=[^}]*outline:\s*var\(--hd-focus-outline\))(?=[^}]*outline-offset:\s*var\(--hd-focus-offset\))/)
    expect(css).toMatch(/\.scroll\s*\{(?=[^}]*max-height:\s*30dvh)(?=[^}]*overflow:\s*auto)/)
  })

  it('shows which row is picked, to a screen reader too', async () => {
    const { search } = controlled()
    const { container } = render(<Example search={search} selectedId="11" />)
    expect(screen.getByRole('option', { name: /Charter Dispute/ }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('option', { name: /Series A/ }).getAttribute('aria-selected')).toBe('false')
    await expectNoAxeViolations(container)
  })

  // A row's own muted text fails AA on the fill the arrow keys put on it, and on a picked row's Inactive selection, so
  // everything in such a row takes the row's colour, but for the marks, which keep the text colour they reach AA with
  it('draws everything in a row on a fill in the fill\'s colour, and the marks in theirs', () => {
    const css = pickerCss.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(css).toMatch(/\.option\[data-focused\]\s*\{(?=[^}]*background:\s*var\(--hd-accent-fill\))(?=[^}]*color:\s*var\(--hd-accent-on-fill\))(?=[^}]*--hd-icon-on-fill:\s*currentColor)/)
    expect(css).toMatch(/\.option\[data-selected\]\s*\{(?=[^}]*background:\s*var\(--hd-selection-inactive\))(?=[^}]*color:\s*var\(--hd-text\))/)
    expect(css).toMatch(/\.option:is\(\[data-focused\], \[data-selected\]\) :not\(\.mark\)\s*\{[^}]*color:\s*inherit/)
    expect(css).toMatch(/\.mark\s*\{(?=[^}]*background:\s*var\(--hd-mark\))(?=[^}]*color:\s*var\(--hd-text\))/)
  })
})

describe('Highlight', () => {
  it('marks each word of the query wherever it matches, in any case', () => {
    const { container } = render(<Highlight text="Charter Dispute – MV Northern Star" query="  char  star " />)
    expect(container.textContent).toBe('Charter Dispute – MV Northern Star')
    expect([...container.querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual(['Char', 'Star'])
  })

  it('keeps the text as text, and takes the query literally', () => {
    const { container } = render(<Highlight text="<b>1001.1</b> and 1001x1" query="1001.1 <b>" />)
    expect(container.querySelector('b')).toBeNull()
    expect(container.textContent).toBe('<b>1001.1</b> and 1001x1')
    expect([...container.querySelectorAll('mark')].map((mark) => mark.textContent)).toEqual(['<b>', '1001.1'])
  })

  it('marks nothing for an empty query', () => {
    const { container } = render(<Highlight text="Charter Dispute" query=" " />)
    expect(container.querySelector('mark')).toBeNull()
    expect(container.textContent).toBe('Charter Dispute')
  })
})

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DEFAULT_SHOWN, forgetLayout, keepLayout, readLayout, shownIds, useColumnLayout } from './columnLayouts'

const OFFERED = ['title', 'docNumber', 'matter', 'author', 'edited', 'size']
const kept = (kind: string) => window.localStorage.getItem(`healdan.columns.${kind}`)

beforeEach(() => window.localStorage.clear())
afterEach(() => vi.restoreAllMocks())

describe('reading a layout', () => {
  it('has no layout when nothing is kept, storage is refused, or the value is of another shape or version', () => {
    expect(readLayout('emails')).toEqual({ widths: {} })
    const put = (text: string) => window.localStorage.setItem('healdan.columns.emails', text)
    for (const text of ['{"v":2,"widths":{}}', '[]', 'null', 'nope', '{"v":1,"widths":{"a":"x"}}', '{"v":1,"widths":null}',
      '{"v":1,"widths":{"a":0}}', '{"v":1,"shown":"title","widths":{}}', '{"v":1,"shown":[1],"widths":{}}']) {
      put(text)
      expect(readLayout('emails'), text).toEqual({ widths: {} })
    }
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('refused') })
    expect(readLayout('emails')).toEqual({ widths: {} })
  })

  it('reads what keepLayout wrote, and forgetLayout forgets it', () => {
    keepLayout('documents', { shown: ['title', 'author'], widths: { author: 90 } })
    expect(kept('documents')).toBe('{"v":1,"shown":["title","author"],"widths":{"author":90}}')
    expect(readLayout('documents')).toEqual({ shown: ['title', 'author'], widths: { author: 90 } })
    forgetLayout('documents')
    expect(kept('documents')).toBeNull()
  })
})

describe('the columns shown', () => {
  it('puts Title first and drops ids the kind does not offer', () => {
    expect(shownIds('documents', OFFERED, { shown: ['author', 'title', 'nope'], widths: {} })).toEqual(['title', 'author'])
    expect(shownIds('documents', OFFERED, { shown: ['author'], widths: {} })).toEqual(['title', 'author'])
  })

  it('uses the kind defaults when no columns were chosen', () => {
    expect(shownIds('folder', OFFERED, { widths: {} })).toEqual(['title', 'docNumber', 'author', 'edited'])
  })

  it('beside skips a column the list does not offer', () => {
    expect(shownIds('beside', ['title', 'docNumber', 'edited'], { shown: ['title', 'lastUsed', 'edited'], widths: {} })).toEqual(['title', 'edited'])
  })

  it('lists that are not of documents show what is offered and keep no shown', () => {
    expect(shownIds('matters', ['a', 'b'], { shown: ['b'], widths: {} })).toEqual(['a', 'b'])
  })
})

describe('useColumnLayout', () => {
  it('keeps no shown for a resize alone, so a later release defaults still apply', () => {
    const { result } = renderHook(() => useColumnLayout('emails', OFFERED))
    act(() => result.current.resized({ matter: 240 }))
    expect(kept('emails')).toBe('{"v":1,"widths":{"matter":240}}')
    expect(result.current.widths).toEqual({ matter: 240 })
    const before = DEFAULT_SHOWN.emails
    DEFAULT_SHOWN.emails = ['title', 'size']
    try {
      const again = renderHook(() => useColumnLayout('emails', OFFERED))
      expect(again.result.current.shown).toEqual(['title', 'size'])
    } finally {
      DEFAULT_SHOWN.emails = before
    }
  })

  it('merges resized widths over kept ones', () => {
    const { result } = renderHook(() => useColumnLayout('folder', OFFERED))
    act(() => result.current.resized({ author: 100 }))
    act(() => result.current.resized({ author: 120, edited: 80 }))
    expect(result.current.widths).toEqual({ author: 120, edited: 80 })
  })

  it('keeps shown once a column is shown, hidden or moved, and a column added later starts hidden', () => {
    const { result } = renderHook(() => useColumnLayout('folder', OFFERED))
    act(() => result.current.show('size'))
    expect(result.current.shown).toEqual(['title', 'docNumber', 'author', 'edited', 'size'])
    // The kept list holds Version too: this OFFERED set lacks it, but the kept layout isn't rewritten as the shown one
    expect(kept('folder')).toBe('{"v":1,"shown":["title","docNumber","version","author","edited","size"],"widths":{}}')
    act(() => result.current.hide('docNumber'))
    act(() => result.current.hide('title'))
    expect(result.current.shown).toEqual(['title', 'author', 'edited', 'size'])
    act(() => result.current.move('size', 1))
    expect(result.current.shown).toEqual(['title', 'size', 'author', 'edited'])
    expect(JSON.parse(kept('folder')!).shown).toEqual(['title', 'size', 'version', 'author', 'edited'])
  })

  it('moves within the shown columns, never before Title', () => {
    const { result } = renderHook(() => useColumnLayout('folder', OFFERED))
    act(() => result.current.move('author', 0))
    expect(result.current.shown).toEqual(['title', 'author', 'docNumber', 'edited'])
    act(() => result.current.move('author', 99))
    expect(result.current.shown).toEqual(['title', 'docNumber', 'edited', 'author'])
    act(() => result.current.move('title', 2))
    expect(result.current.shown[0]).toBe('title')
  })

  it('a move beside keeps the place of a column the list skips', () => {
    keepLayout('beside', { shown: ['title', 'lastUsed', 'docNumber', 'edited'], widths: {} })
    const { result } = renderHook(() => useColumnLayout('beside', ['title', 'docNumber', 'edited']))
    expect(result.current.shown).toEqual(['title', 'docNumber', 'edited'])
    act(() => result.current.move('edited', 1))
    expect(result.current.shown).toEqual(['title', 'edited', 'docNumber'])
    expect(JSON.parse(kept('beside')!).shown).toEqual(['title', 'edited', 'lastUsed', 'docNumber'])
  })

  it('resetWidth forgets one width and reset forgets the whole layout', () => {
    const { result } = renderHook(() => useColumnLayout('folder', OFFERED))
    act(() => result.current.resized({ author: 100, edited: 80 }))
    act(() => result.current.resetWidth('author'))
    expect(result.current.widths).toEqual({ edited: 80 })
    act(() => result.current.hide('author'))
    act(() => result.current.reset())
    expect(kept('folder')).toBeNull()
    expect(result.current.widths).toEqual({})
    expect(result.current.shown).toEqual(['title', 'docNumber', 'author', 'edited'])
  })

  it('a kind that is not of documents keeps widths only', () => {
    const { result } = renderHook(() => useColumnLayout('matters', ['a', 'b']))
    act(() => result.current.show('c'))
    act(() => result.current.hide('a'))
    act(() => result.current.move('b', 0))
    expect(kept('matters')).toBeNull()
    act(() => result.current.resized({ a: 50 }))
    expect(kept('matters')).toBe('{"v":1,"widths":{"a":50}}')
    expect(result.current.shown).toEqual(['a', 'b'])
  })

  it('two changes in one event both count', () => {
    const { result } = renderHook(() => useColumnLayout('folder', OFFERED))
    act(() => {
      result.current.show('size')
      result.current.hide('author')
    })
    expect(JSON.parse(kept('folder')!).shown).toEqual(['title', 'docNumber', 'version', 'edited', 'size'])
  })

  it('a change that changes nothing writes nothing, and freezes no defaults', () => {
    const { result } = renderHook(() => useColumnLayout('folder', OFFERED))
    act(() => {
      result.current.move('docNumber', 1)
      result.current.show('author')
      result.current.hide('size')
      result.current.hide('title')
    })
    expect(kept('folder')).toBeNull()
    act(() => result.current.resized({ author: 100 }))
    act(() => result.current.move('docNumber', 1))
    expect(kept('folder')).toBe('{"v":1,"widths":{"author":100}}')
  })

  it('a column offered later starts hidden in a layout that has shown', () => {
    keepLayout('folder', { shown: ['title', 'author'], widths: {} })
    const { result } = renderHook(() => useColumnLayout('folder', [...OFFERED, 'client']))
    expect(result.current.shown).toEqual(['title', 'author'])
  })

  it('a shown stored for a kind that is not of documents is ignored and not written back', () => {
    window.localStorage.setItem('healdan.columns.matters', '{"v":1,"shown":["b"],"widths":{"a":5}}')
    expect(readLayout('matters')).toEqual({ widths: { a: 5 } })
    const { result } = renderHook(() => useColumnLayout('matters', ['a', 'b']))
    expect(result.current.shown).toEqual(['a', 'b'])
    act(() => result.current.resized({ b: 7 }))
    expect(kept('matters')).toBe('{"v":1,"widths":{"a":5,"b":7}}')
  })

  it('reads again when the kind changes', () => {
    keepLayout('all', { widths: { matter: 10 } })
    const { result, rerender } = renderHook(({ kind }) => useColumnLayout(kind, OFFERED), { initialProps: { kind: 'folder' as 'folder' | 'all' } })
    expect(result.current.widths).toEqual({})
    rerender({ kind: 'all' })
    expect(result.current.widths).toEqual({ matter: 10 })
  })
})

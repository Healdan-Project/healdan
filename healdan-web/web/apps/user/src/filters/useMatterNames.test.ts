// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { client, document, matter } from '../test/fakeApi'
import { useMatterNames } from './useMatterNames'

const names = (...args: Parameters<typeof useMatterNames>) => renderHook(() => useMatterNames(...args)).result.current

describe('useMatterNames', () => {
  it("names the clients and matters of the list's documents", () => {
    const { clients, matters } = names([document()], undefined)
    expect(clients.get('1001')).toBe('1001 – Saltmarsh Shipping Ltd')
    expect(matters.get('1001.1')).toBe('1001.1 – Charter Dispute')
  })

  it('takes the names the Filters panel was given, which cover results not loaded yet', () => {
    const { clients, matters } = names([], {
      client: [{ value: '1002', displayName: '1002 – Brightwell', count: 1 }],
      matter: [{ value: '1002.1', displayName: '1002.1 – Commercial Lease', count: 1 }],
    })
    expect(clients.get('1002')).toBe('1002 – Brightwell')
    expect(matters.get('1002.1')).toBe('1002.1 – Commercial Lease')
  })

  it("keeps a row's name over an option the server could only give as its number", () => {
    const { clients, matters } = names([document()], {
      client: [{ value: '1001', displayName: '1001', count: 1 }],
      matter: [{ value: '1001.1', displayName: '1001.1', count: 1 }],
    })
    expect(clients.get('1001')).toBe('1001 – Saltmarsh Shipping Ltd')
    expect(matters.get('1001.1')).toBe('1001.1 – Charter Dispute')
  })

  it('passes over a row that is a matter, and a document in none', () => {
    const { clients, matters } = names([matter({ client: client({ number: 1005 }) }), document({ matter: null })], undefined)
    expect(clients.size).toBe(0)
    expect(matters.size).toBe(0)
  })
})

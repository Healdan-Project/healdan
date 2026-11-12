// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { problem } from '../test/fakeApi'
import { StorageBanner } from './StorageBanner'

// A view whose request fails (or, once told to, works), beside the banner.
function Harness({ client, code, status }: { client: QueryClient; code: string; status: number }) {
  return (
    <QueryClientProvider client={client}>
      <Failing code={code} status={status} />
      <StorageBanner />
    </QueryClientProvider>
  )
}

let works = false
function Failing({ code, status }: { code: string; status: number }) {
  useQuery({ queryKey: ['x'], queryFn: () => (works ? 'ok' : Promise.reject(problem(status, code))) })
  return null
}

function setup(code: string, status: number) {
  works = false
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return { client, ...render(<Harness client={client} code={code} status={status} />) }
}

describe('StorageBanner', () => {
  it('shows when a request on screen failed because the storage is unreachable', async () => {
    const { container } = setup('storage-unreachable', 503)
    expect((await screen.findByRole('alert')).textContent).toBe('Files are unavailable for now. Try again in a few minutes.')
    await expectNoAxeViolations(container)
  })

  it('shows nothing for another failure', async () => {
    const { client } = setup('server-error', 500)
    await waitFor(() => expect(client.getQueryState(['x'])?.status).toBe('error'))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('goes away when the request works again', async () => {
    const { client } = setup('storage-unreachable', 503)
    await screen.findByRole('alert')
    works = true
    await client.refetchQueries({ queryKey: ['x'] })
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  })

  it('goes away when the failing view is no longer on screen', async () => {
    const { client, rerender } = setup('storage-unreachable', 503)
    await screen.findByRole('alert')
    rerender(
      <QueryClientProvider client={client}>
        <StorageBanner />
      </QueryClientProvider>,
    )
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  })
})

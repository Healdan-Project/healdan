// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { letter, propertiesOf } from '../test/details'
import { document } from '../test/fakeApi'
import { renderWithRouter } from '../test/renderWithRouter'
import css from './details.module.css?raw'
import { Properties } from './Properties'

describe('Properties', () => {
  it('starts with Location in search results, linking to the folder', async () => {
    await renderWithRouter(<Properties document={letter} fields={propertiesOf()} context="search" />)

    const list = await screen.findByRole('group', { name: 'Properties' })
    expect(within(list).getAllByRole('term')[0]!.textContent).toBe('Location')
    const link = within(list).getByRole('link', { name: 'Correspondence' })
    expect(link.getAttribute('href')).toBe('/app/folders/111')
  })

  it('has no Location in a folder, where the folder is the view', async () => {
    await renderWithRouter(<Properties document={letter} fields={propertiesOf()} context="folder" />)

    const list = await screen.findByRole('group', { name: 'Properties' })
    expect(within(list).queryByText('Location')).toBeNull()
    expect(within(list).queryByRole('link')).toBeNull()
  })

  it("shows what a property has not got as None, and leaves out what isn't in the list", async () => {
    const bare = document({ docNumber: 1, matter: null, author: null })
    await renderWithRouter(
      <Properties document={bare} fields={propertiesOf({ docsubclass: null, author: null }).filter((f) => f.name !== 'typist')} context="folder" />,
    )

    const list = await screen.findByRole('group', { name: 'Properties' })
    const value = (label: string) => within(list).getByText(label).nextElementSibling?.textContent
    expect(value('Subclass')).toBe('None')
    expect(value('Author')).toBe('None')
    expect(within(list).queryByText('Typist')).toBeNull()
  })

  it('shows who a user property is when it has no name, and None only when there is no user', async () => {
    const bare = document({ docNumber: 1, author: { username: 'jclarke', displayName: 'jclarke' } })
    await renderWithRouter(<Properties document={bare} fields={propertiesOf({ author: { id: 3, name: null }, typist: { id: 5, name: null } })} context="folder" />)

    const list = await screen.findByRole('group', { name: 'Properties' })
    const value = (label: string) => within(list).getByText(label).nextElementSibling?.textContent
    // The Author is the document's, which the server names by its username when it has no name
    expect(value('Author')).toBe('jclarke')
    expect(value('Typist')).toBe('Unnamed User')
  })

  it('styles the Location link as a link with the shared focus outline', () => {
    expect(css).toMatch(/\.property a\s*{[^}]*color: var\(--hd-accent-text\)/)
    expect(css).toMatch(/\.property a:focus-visible\s*{[^}]*outline: var\(--hd-focus-outline\)/)
  })

  it('shows no email field for a document that has none of them', async () => {
    await renderWithRouter(<Properties document={letter} fields={propertiesOf()} context="folder" />)

    const list = await screen.findByRole('group', { name: 'Properties' })
    for (const label of ['From', 'To', 'Sent', 'Received']) expect(within(list).queryByText(label)).toBeNull()
  })
})

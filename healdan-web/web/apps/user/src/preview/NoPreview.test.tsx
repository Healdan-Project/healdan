// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { document } from '../test/fakeApi'
import { renderPreview } from '../test/renderPreview'
import { NoPreview } from './NoPreview'

describe('NoPreview', () => {
  it('says there is no preview, with Open and Download', () => {
    renderPreview(<NoPreview document={document({ name: 'Archive.zip' })} />)
    expect(screen.getByText("There's no preview for this kind of file.")).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Open' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Download' })).toBeTruthy()
  })

  it('shows the icon of the file\'s kind, in its colour', () => {
    const { container } = renderPreview(<NoPreview document={document({ name: 'Board Deck.pptx' })} />)
    const icon = container.querySelector('svg')!
    expect(icon.getAttribute('class')).toContain('tabler-icon-file-type-ppt')
    expect(icon.getAttribute('data-kind')).toBe('presentation')
  })

  it('can say something else, as when a preview could not be drawn', () => {
    renderPreview(<NoPreview document={document()} message="The preview couldn't be drawn." />)
    expect(screen.getByText("The preview couldn't be drawn.")).toBeTruthy()
  })

  it('passes axe', async () => {
    const { container } = renderPreview(<NoPreview document={document({ name: 'Archive.zip' })} />)
    await expectNoAxeViolations(container)
  })
})

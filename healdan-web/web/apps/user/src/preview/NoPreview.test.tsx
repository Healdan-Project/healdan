// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoAxeViolations } from '../test/axe'
import { document } from '../test/fakeApi'
import { NoPreview } from './NoPreview'

describe('NoPreview', () => {
  it('says there is no preview, with Open and Download', () => {
    render(<NoPreview document={document({ name: 'Archive.zip' })} />)
    expect(screen.getByText("There's no preview for this kind of file.")).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Open' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Download' })).toBeTruthy()
  })

  it('can say something else, as when a preview could not be drawn', () => {
    render(<NoPreview document={document()} message="The preview couldn't be drawn." />)
    expect(screen.getByText("The preview couldn't be drawn.")).toBeTruthy()
  })

  it('passes axe', async () => {
    const { container } = render(<NoPreview document={document({ name: 'Archive.zip' })} />)
    await expectNoAxeViolations(container)
  })
})

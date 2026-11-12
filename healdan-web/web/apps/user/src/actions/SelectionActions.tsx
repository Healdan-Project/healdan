// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Button, Icon, t } from '@healdan/ui'
import { PreviewButton } from '../preview/PreviewButton'
import { CheckOutButton } from './CheckoutControls'
import { MoreMenu } from './MoreMenu'
import { useOpenInApp } from './OpenInAppState'

/**
 * The toolbar's buttons for the selected document (spec 6.1): Open, Preview, Download and Check Out, all disabled with
 * none or several selected, then More (⋯), which acts on `selected` (the one document by default).
 */
export function SelectionActions({
  document,
  selected = document ? [document] : [],
}: {
  document: components['schemas']['DocumentView'] | undefined
  selected?: components['schemas']['DocumentView'][]
}) {
  const app = useOpenInApp()
  const may = document?.canOpen === true
  return (
    <>
      <Button isDisabled={!may} onPress={() => document && app.open(document)}>
        <Icon name="external" size={14} /> {t('details.open')}
      </Button>
      <PreviewButton />
      <Button isDisabled={!may} onPress={() => document && app.download(document)}>
        <Icon name="download" size={14} /> {t('details.download')}
      </Button>
      <CheckOutButton document={document} />
      <MoreMenu documents={selected} />
    </>
  )
}

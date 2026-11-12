// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Button, Icon, t } from '@healdan/ui'
import { PreviewButton } from '../preview/PreviewButton'
import { CheckOutButton } from './CheckoutControls'
import { useOpenInApp } from './OpenInAppState'

/**
 * The toolbar's buttons for the selected document (spec 6.1): Open, Preview, Download and Check Out, all disabled with
 * none or several selected. More (⋯) is added after them by the task that builds it.
 */
export function SelectionActions({ document }: { document: components['schemas']['DocumentView'] | undefined }) {
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
    </>
  )
}

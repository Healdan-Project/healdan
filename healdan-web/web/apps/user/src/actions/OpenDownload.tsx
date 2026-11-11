// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { Button, t } from '@healdan/ui'
import { useOpenInApp } from './OpenInAppState'

/**
 * The two buttons every place that shows a document offers beside it: the details, Quick Look and a preview that can't
 * be shown. Both need the Download permission (`canOpen`). Open is the current file, so a version has Download only.
 */
export function OpenDownload({ document, version }: { document: components['schemas']['DocumentView']; version?: string }) {
  const app = useOpenInApp()
  return (
    <>
      <Button isDisabled={!document.canOpen || version !== undefined} onPress={() => app.open(document)}>
        {t('details.open')}
      </Button>
      <Button isDisabled={!document.canOpen} onPress={() => app.download(document, version)}>
        {t('details.download')}
      </Button>
    </>
  )
}

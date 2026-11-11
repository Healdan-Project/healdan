// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Button, t } from '@healdan/ui'

// The two buttons every place that shows a document offers beside it: the details, Quick Look and a preview that
// can't be shown. They're disabled until opening in the app and downloading are built (Task 18), which changes this one.
export function OpenDownload() {
  return (
    <>
      <Button isDisabled>{t('details.open')}</Button>
      <Button isDisabled>{t('details.download')}</Button>
    </>
  )
}

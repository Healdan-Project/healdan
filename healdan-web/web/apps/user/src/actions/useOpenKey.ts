// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { useOpenInApp } from './OpenInAppState'

/**
 * What a list does with Enter and a double-click on a document's row (rows are keyed `d<number>`; spec 10.5 and 10.6):
 * selects it and opens it in its app, or downloads a kind the helper doesn't open. A person without the Download
 * permission only has it selected.
 */
export function useOpenKey(documents: components['schemas']['DocumentView'][], select: (keys: Set<string>) => void): (key: string) => void {
  const app = useOpenInApp()
  return (key) => {
    const document = key.startsWith('d') ? documents.find((one) => `d${one.docNumber}` === key) : undefined
    select(new Set([key]))
    if (document?.canOpen) app.open(document)
  }
}

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { useOpenInApp } from './OpenInAppState'

/**
 * What a list does with Enter and a double-click on a document's row (rows are keyed `d<number>`; spec 10.5 and 10.6):
 * selects it and opens it in its app, or downloads a kind the helper doesn't open. It gives back whether it was a document
 * the person may open; a person without the Download permission, a folder and a row that isn't drawn get false, and the
 * view does what it does with a row it can't open.
 */
export function useOpenKey(documents: components['schemas']['DocumentView'][], select: (keys: Set<string>) => void): (key: string) => boolean {
  const app = useOpenInApp()
  return (key) => {
    const document = key.startsWith('d') ? documents.find((one) => `d${one.docNumber}` === key) : undefined
    select(new Set([key]))
    if (!document?.canOpen) return false
    app.open(document)
    return true
  }
}

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useContext, useMemo, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { t, toastWithLink } from '@healdan/ui'
import { useApi } from '../services'
import { download } from './download'
import { launch } from './launch'
import { handlesInApp, healdanLink, HELPER_DOCS } from './openInApp'

type DocumentView = components['schemas']['DocumentView']

export interface OpenInApp {
  /** Opens a document in the helper and says what to do if nothing opened; a kind the helper doesn't open downloads instead */
  open: (document: DocumentView) => void
  /** Downloads a document's file, or one version's */
  download: (document: DocumentView, version?: string) => void
}

const Context = createContext<OpenInApp | null>(null)

/**
 * Open in App for the whole app (spec 10.6): double-click, Enter and the Open buttons come here. The browser can't tell
 * whether the helper took the link, so a notice says what to do if nothing opened, as the old UI's did. It's a toast with
 * a link, shown in the Toast region, which is above Quick Look.
 */
export function OpenInAppProvider({ children }: { children: ReactNode }) {
  const api = useApi()
  const value = useMemo<OpenInApp>(
    () => ({
      open: (document) => {
        if (handlesInApp(document.name)) {
          launch(healdanLink(document.docNumber, window.location.host))
          toastWithLink(t('open.notice'), HELPER_DOCS)
        } else download(api, document)
      },
      download: (document, version) => download(api, document, version),
    }),
    [api],
  )
  return <Context.Provider value={value}>{children}</Context.Provider>
}

export function useOpenInApp(): OpenInApp {
  const value = useContext(Context)
  if (!value) throw new Error('useOpenInApp needs an OpenInAppProvider')
  return value
}

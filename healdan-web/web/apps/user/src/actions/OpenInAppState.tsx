// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { Button, Icon, t } from '@healdan/ui'
import { useApi } from '../services'
import { download } from './download'
import { launch } from './launch'
import { handlesInApp, healdanLink, HELPER_DOCS } from './openInApp'
import styles from './actions.module.css'

type DocumentView = components['schemas']['DocumentView']

export interface OpenInApp {
  /** Opens a document in the helper and says what to do if nothing opened; a kind the helper doesn't open downloads instead */
  open: (document: DocumentView) => void
  /** Downloads a document's file, or one version's */
  download: (document: DocumentView, version?: string) => void
}

const Context = createContext<OpenInApp | null>(null)

/** How long the notice stays when it isn't dismissed */
const NOTICE_MS = 15_000

/**
 * Open in App for the whole app (spec 10.6): double-click, Enter and the Open buttons come here. The browser can't tell
 * whether the helper took the link, so the notice says what to do if nothing opened, as the old UI's did.
 */
export function OpenInAppProvider({ children }: { children: ReactNode }) {
  const api = useApi()
  const [notice, setNotice] = useState(0)
  const value = useMemo<OpenInApp>(
    () => ({
      open: (document) => {
        if (handlesInApp(document.name)) {
          launch(healdanLink(document.docNumber, window.location.host))
          // A number, so opening a second document shows the notice again if it was dismissed
          setNotice((n) => n + 1)
        } else download(api, document)
      },
      download: (document, version) => download(api, document, version),
    }),
    [api],
  )
  useEffect(() => {
    if (notice === 0) return
    const timer = setTimeout(() => setNotice(0), NOTICE_MS)
    return () => clearTimeout(timer)
  }, [notice])
  return (
    <Context.Provider value={value}>
      {children}
      {notice > 0 && (
        <div role="status" className={styles.notice}>
          <span>
            {t('open.noticeBefore')}{' '}
            <a href={HELPER_DOCS} target="_blank" rel="noopener noreferrer">
              {t('open.noticeLink')}
            </a>
            .
          </span>
          <Button variant="quiet" aria-label={t('toast.dismiss')} onPress={() => setNotice(0)}>
            <Icon name="close" />
          </Button>
        </div>
      )}
    </Context.Provider>
  )
}

export function useOpenInApp(): OpenInApp {
  const value = useContext(Context)
  if (!value) throw new Error('useOpenInApp needs an OpenInAppProvider')
  return value
}

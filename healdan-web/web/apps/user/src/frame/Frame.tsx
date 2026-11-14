// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Outlet, useRouterState } from '@tanstack/react-router'
import { useEffect, useRef, useState } from 'react'
import { OpenInAppProvider } from '../actions/OpenInAppState'
import { DetailsTabProvider } from '../details/DetailsTab'
import { FilingProvider } from '../filing/FilingProvider'
import { OrganisingProvider } from '../organising/OrganisingProvider'
import { QuickLookProvider } from '../preview/QuickLookState'
import { useSignedOut } from '../services'
import { LeftPane } from './LeftPane'
import { focusInto, isInnermost, openLayer, useNarrow } from './narrow'
import { RightRegionProvider } from './RightRegion'
import { SignedOut } from './SignedOut'
import { StorageBanner } from './StorageBanner'
import { NAVIGATION_BUTTON, TopBar } from './TopBar'
import styles from './Frame.module.css'

// The top bar, the left pane and the main area, which holds the route's view. Once a request has said the person is
// signed out, nothing else is worth showing. In a narrow window the left pane folds away behind a button of the top bar and
// opens over the list (spec 5), where Esc closes it and following a link in it does too.
export function Frame() {
  const signedOut = useSignedOut()
  if (signedOut) return <SignedOut />
  return (
    <RightRegionProvider>
      <QuickLookProvider>
        <OpenInAppProvider>
          <FilingProvider>
            <OrganisingProvider>
              <DetailsTabProvider>
                <Layout />
              </DetailsTabProvider>
            </OrganisingProvider>
          </FilingProvider>
        </OpenInAppProvider>
      </QuickLookProvider>
    </RightRegionProvider>
  )
}

function Layout() {
  const narrow = useNarrow()
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  // The address the pane was opened at: following a link in it goes to a view that has the width now, so the pane closes
  const [openedAt, setOpenedAt] = useState<string>()
  const paneOpen = openedAt === pathname
  const over = useRef<HTMLDivElement>(null)
  const setPaneOpen = (open: boolean) => setOpenedAt(open ? pathname : undefined)

  // Esc closes the pane, and the focus goes back to the button that opened it. A menu or tree popup in it takes Esc for itself.
  useEffect(() => {
    if (!narrow || !paneOpen) return
    const closeLayer = openLayer('pane')
    focusInto(over.current)
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || !isInnermost('pane')) return
      event.preventDefault()
      setPaneOpen(false)
      document.querySelector<HTMLElement>(`[${NAVIGATION_BUTTON}]`)?.focus()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      closeLayer()
      document.removeEventListener('keydown', onKeyDown)
    }
    // setPaneOpen is made from the address each render
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [narrow, paneOpen])

  const open = narrow && paneOpen
  return (
    <div className={`${styles.frame} ${narrow ? styles.narrow : ''}`}>
      <TopBar navigation={narrow ? { open, onToggle: () => setPaneOpen(!paneOpen) } : undefined} />
      {(!narrow || open) && (
        <div ref={over} className={narrow ? styles.over : styles.pane}>
          <LeftPane />
        </div>
      )}
      <main className={styles.main}>
        <StorageBanner />
        <Outlet />
      </main>
    </div>
  )
}

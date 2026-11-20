// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Outlet, useRouterState } from '@tanstack/react-router'
import { useEffect, useId, useRef, useState } from 'react'
import { focusInto, isInnermost, NoAdminAccess, openLayer, SignedOut, useNarrow, useSignedOut } from '@healdan/shell'
import { useRefused } from '../access'
import { LeftPane } from './LeftPane'
import { NAVIGATION_BUTTON, TopBar } from './TopBar'
import styles from './Frame.module.css'

/**
 * The top bar, the left pane and the main area, which holds the route's view (spec 7.1). Once a call has said the person
 * is signed out (a 401), or no longer an administrator (`403 admins-only`, access.tsx), nothing else is worth showing:
 * the frame and every screen in it go, and with them their polls.
 */
export function Frame() {
  const signedOut = useSignedOut()
  const refused = useRefused()
  if (signedOut) return <SignedOut />
  if (refused) return <NoAdminAccess />
  return <Layout />
}

// As the User app's frame: in a narrow window the left pane folds away behind a button of the top bar and opens over the
// main area, where Esc closes it and following a link in it does too.
function Layout() {
  const narrow = useNarrow()
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  // The address the pane was opened at: following a link in it goes to a view that has the width now, so the pane closes
  const [openedAt, setOpenedAt] = useState<string>()
  const paneOpen = openedAt === pathname
  const over = useRef<HTMLDivElement>(null)
  const paneId = useId()
  const setPaneOpen = (open: boolean) => setOpenedAt(open ? pathname : undefined)

  // Esc closes the pane, and the focus goes back to the button that opened it. A menu in it takes Esc for itself.
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
        <div ref={over} id={paneId} className={narrow ? styles.over : styles.pane}>
          <LeftPane />
        </div>
      )}
      <main className={styles.main}>
        <Outlet />
      </main>
    </div>
  )
}

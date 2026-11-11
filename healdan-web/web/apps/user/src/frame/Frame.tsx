// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Outlet } from '@tanstack/react-router'
import { OpenInAppProvider } from '../actions/OpenInAppState'
import { FilingProvider } from '../filing/FilingProvider'
import { OrganisingProvider } from '../organising/OrganisingProvider'
import { QuickLookProvider } from '../preview/QuickLookState'
import { useSignedOut } from '../services'
import { LeftPane } from './LeftPane'
import { RightRegionProvider } from './RightRegion'
import { SignedOut } from './SignedOut'
import { StorageBanner } from './StorageBanner'
import { TopBar } from './TopBar'
import styles from './Frame.module.css'

// The top bar, the left pane and the main area, which holds the route's view. Once a request has said the person is
// signed out, nothing else is worth showing.
export function Frame() {
  const signedOut = useSignedOut()
  if (signedOut) return <SignedOut />
  return (
    <RightRegionProvider>
      <QuickLookProvider>
        <OpenInAppProvider>
          <FilingProvider>
            <OrganisingProvider>
              <div className={styles.frame}>
                <TopBar />
                <LeftPane />
                <main className={styles.main}>
                  <StorageBanner />
                  <Outlet />
                </main>
              </div>
            </OrganisingProvider>
          </FilingProvider>
        </OpenInAppProvider>
      </QuickLookProvider>
    </RightRegionProvider>
  )
}

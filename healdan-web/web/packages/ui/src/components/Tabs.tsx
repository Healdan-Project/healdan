// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { Tab, TabList, TabPanel, Tabs as AriaTabs } from 'react-aria-components'
import styles from './Tabs.module.css'

export interface TabItem {
  /** Stable for the tab: what `selected` and `onSelect` name */
  id: string
  /** The tab's label, which is its accessible name and the panel's */
  label: string
  /** What the tab shows; only the selected tab's is drawn */
  content: ReactNode
}

export interface TabsProps {
  /** Names the strip of tabs for a screen reader */
  label: string
  tabs: TabItem[]
  /** The tab shown: the caller keeps it, so it can outlive the panel */
  selected: string
  onSelect: (id: string) => void
}

/**
 * Tabs drawn as the Healdan Helper's segmented control (its polish spec, section 6.2): equal segments on a pill-coloured
 * track, the selected one raised. React Aria gives the roles, the arrow keys between the tabs and Tab into the panel.
 */
export function Tabs({ label, tabs, selected, onSelect }: TabsProps) {
  return (
    <AriaTabs selectedKey={selected} onSelectionChange={(key) => onSelect(String(key))} className={styles.tabs}>
      <TabList aria-label={label} className={styles.list}>
        {tabs.map((tab) => (
          <Tab key={tab.id} id={tab.id} className={styles.tab}>
            {tab.label}
          </Tab>
        ))}
      </TabList>
      {tabs.map((tab) => (
        <TabPanel key={tab.id} id={tab.id} className={styles.panel}>
          {tab.content}
        </TabPanel>
      ))}
    </AriaTabs>
  )
}

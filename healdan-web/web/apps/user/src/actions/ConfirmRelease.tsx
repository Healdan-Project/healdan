// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId, type ReactNode } from 'react'
import type { components } from '@healdan/api'
import { Button, Dialog, formatDateTime, Icon, plural, t } from '@healdan/ui'
import { fileIcon } from '../kindIcons'
import { computerLabel } from './checkout'
import styles from './actions.module.css'

type DocumentView = components['schemas']['DocumentView']

export interface ConfirmReleaseProps {
  /** The person's own check-outs, in the selection's order: those their other computers hold, and any the web page holds */
  documents: DocumentView[]
  onConfirm: () => void
  onCancel: () => void
  /** Signed-in Devices, from the sentence on a lost or stolen computer */
  onShowDevices: () => void
}

interface Computer {
  /** Its short name, "MacBook" (computerLabel) */
  name: string
  documents: DocumentView[]
}

/**
 * The documents each computer holds, by computer in the selection's order. They're all the person's own, so one with a
 * device is held on a computer (holdState's `yourDevice`); the web page's are left out.
 */
function byComputer(documents: readonly DocumentView[]): Computer[] {
  const computers = new Map<string | null, Computer>()
  for (const document of documents) {
    const hold = document.hold
    if (!hold?.device) continue
    const key = hold.heldOn?.name ?? null
    const computer = computers.get(key) ?? { name: computerLabel(hold).computer, documents: [] }
    computer.documents.push(document)
    computers.set(key, computer)
  }
  return [...computers.values()]
}

/** Each document: its icon, name and doc #, the app that holds it, and when it was last saved to Healdan */
function DocumentRows({ documents, labelledBy }: { documents: DocumentView[]; labelledBy?: string }) {
  return (
    <ul className={styles.releaseList} aria-labelledby={labelledBy}>
      {documents.map((document) => (
        <li key={document.docNumber} className={styles.releaseRow}>
          <Icon {...fileIcon(document.name)} />
          <span className={styles.releaseName}>{document.name}</span>
          <span className={styles.releaseMeta}>
            <span>{t('preview.number', { n: document.docNumber })}</span>
            {document.hold?.heldOn?.app && <span>{document.hold.heldOn.app}</span>}
            <span>{t('release.saved', { when: formatDateTime(document.lastModified) })}</span>
          </span>
        </li>
      ))}
    </ul>
  )
}

// The sentence is filled with this mark in place of {devices}, and cut there for the button
const MARK = '\u0000'

function withButton(sentence: string, button: ReactNode): ReactNode {
  const [before, ...after] = sentence.split(MARK)
  return (
    <>
      {before}
      {button}
      {after.join('')}
    </>
  )
}

/**
 * The warning before releasing check-outs that another of the person's computers holds (spec 5.4): what is released, by
 * computer, and that changes there which haven't reached Healdan are lost. Cancel has the focus, so Enter releases nothing,
 * and Esc is Cancel; the red button names what it does and counts every document, the web page's too, which aren't listed.
 */
export function ConfirmRelease({ documents, onConfirm, onCancel, onShowDevices }: ConfirmReleaseProps) {
  const ids = useId()
  const computers = byComputer(documents)
  // One computer is named throughout; two or more are "those computers", with a list for each
  const only = computers.length === 1 ? computers[0] : undefined
  const listed = computers.reduce((count, computer) => count + computer.documents.length, 0)
  const title =
    only && documents.length === 1
      ? t('release.titleOne', { name: documents[0]!.name, computer: only.name })
      : plural('releaseTitle', documents.length, { computer: only?.name ?? t('release.otherComputers') })
  const devices = (
    <button type="button" className={styles.link} onClick={onShowDevices}>
      {t('release.devices')}
    </button>
  )

  return (
    <Dialog
      role="alertdialog"
      size="wide"
      title={title}
      icon={<Icon name="alertTriangle" size={24} className={styles.warningSign} />}
      isOpen
      onOpenChange={(open) => !open && onCancel()}
    >
      {(close) => (
        <>
          {only ? (
            <>
              <p className={styles.releaseText}>{plural('releaseHeldOn', only.documents.length, { computer: only.name })}</p>
              <DocumentRows documents={only.documents} />
            </>
          ) : (
            computers.map((computer, i) => (
              <div key={i}>
                <h3 id={`${ids}-${i}`} className={styles.releaseComputer}>
                  {t('release.on', { computer: computer.name })}
                </h3>
                <DocumentRows documents={computer.documents} labelledBy={`${ids}-${i}`} />
              </div>
            ))
          )}
          <div className={styles.releaseLoss}>
            <p>
              <strong>{only ? t('release.lostOne', { computer: only.name }) : t('release.lostMany')}</strong>
            </p>
            <p>{only ? plural('releaseNoMore', listed, { computer: only.name }) : plural('releaseNoMoreMany', listed)}</p>
            <p>{only ? plural('releaseRescue', listed) : t('release.rescueMany')}</p>
          </div>
          <p className={styles.releaseNote}>
            {withButton(only ? t('release.stolenOne', { computer: only.name, devices: MARK }) : t('release.stolenMany', { devices: MARK }), devices)}
          </p>
          <div className={styles.releaseActions}>
            <Button autoFocus onPress={close}>
              {t('common.cancel')}
            </Button>
            <Button
              variant="danger"
              onPress={() => {
                close()
                onConfirm()
              }}
            >
              {plural('releaseConfirm', documents.length)}
            </Button>
          </div>
        </>
      )}
    </Dialog>
  )
}

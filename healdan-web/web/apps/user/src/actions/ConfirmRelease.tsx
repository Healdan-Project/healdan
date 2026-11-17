// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId, type ReactNode } from 'react'
import { Button, Dialog, formatDateTime, Icon, plural, t } from '@healdan/ui'
import { fileIcon } from '../kindIcons'
import { computerLabel, type ReleaseTarget } from './checkout'
import styles from './actions.module.css'

export interface ConfirmReleaseProps {
  /**
   * The person's own check-outs, in the selection's order, each with ReleaseProvider's decision: those another of their
   * computers holds (`elsewhere`), which are listed, and any the web page holds, which aren't
   */
  documents: ReleaseTarget[]
  onConfirm: () => void
  onCancel: () => void
  /** Signed-in Devices, from the sentence on a lost or stolen computer */
  onShowDevices: () => void
}

interface Computer {
  /** Its short name, "MacBook" (computerLabel) */
  name: string
  documents: ReleaseTarget[]
}

/**
 * The documents another computer holds, by computer in the selection's order; the web page's are left out. A computer is
 * its device, which the holder is always told, not its name: a new Mac restored from a stolen one's backup keeps its
 * name, and two computers are never spoken of as one. Without a device, the name is all there is.
 */
function byComputer(documents: readonly ReleaseTarget[]): Computer[] {
  const computers = new Map<string, Computer>()
  for (const document of documents) {
    if (!document.elsewhere) continue
    const device = document.hold?.device?.id
    const key = device != null ? `device ${device}` : `name ${document.hold?.heldOn?.name ?? ''}`
    const computer = computers.get(key) ?? { name: computerLabel(document.hold).computer, documents: [] }
    computer.documents.push(document)
    computers.set(key, computer)
  }
  return [...computers.values()]
}

/**
 * A computer's documents: each one's icon, name and doc #, the app that holds it, and when it was last saved to Healdan.
 * A long list scrolls, so the list takes the focus, by which the keyboard scrolls it, and is named for it.
 */
function DocumentRows({ computer }: { computer: Computer }) {
  return (
    <ul className={styles.releaseList} tabIndex={0} aria-label={t('release.documentsOn', { computer: computer.name })}>
      {computer.documents.map((document) => (
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
 * computer, and that changes there which haven't reached Healdan are lost, which a screen reader reads as it opens.
 * The red button comes first, as in the app's other dialogs; it names what it does and counts every document, the web
 * page's too, which aren't listed. Cancel, after it, has the focus, so Enter releases nothing, and Esc is Cancel.
 */
export function ConfirmRelease({ documents, onConfirm, onCancel, onShowDevices }: ConfirmReleaseProps) {
  const lossId = useId()
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
      aria-describedby={lossId}
      isOpen
      onOpenChange={(open) => !open && onCancel()}
    >
      {(close) => (
        <>
          {only ? (
            <>
              <p className={styles.releaseText}>{plural('releaseHeldOn', only.documents.length, { computer: only.name })}</p>
              <DocumentRows computer={only} />
            </>
          ) : (
            computers.map((computer, i) => (
              <div key={i}>
                <h3 className={styles.releaseComputer}>{t('release.on', { computer: computer.name })}</h3>
                <DocumentRows computer={computer} />
              </div>
            ))
          )}
          <div id={lossId} className={styles.releaseLoss}>
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
            <Button
              variant="danger"
              onPress={() => {
                close()
                onConfirm()
              }}
            >
              {plural('releaseConfirm', documents.length)}
            </Button>
            <Button autoFocus onPress={close}>
              {t('common.cancel')}
            </Button>
          </div>
        </>
      )}
    </Dialog>
  )
}

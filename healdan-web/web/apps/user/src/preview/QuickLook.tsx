// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useEffect, useRef } from 'react'
import type { components } from '@healdan/api'
import { Button, Icon, Overlay, t } from '@healdan/ui'
import { OpenDownload } from '../actions/OpenDownload'
import { useRightRegion } from '../frame/RightRegion'
import { useDocument } from '../queries'
import { useSelection } from '../selection'
import { PreviewBody } from './PreviewBody'
import { useQuickLook } from './QuickLookState'
import styles from './quickLook.module.css'

type DocumentView = components['schemas']['DocumentView']

export interface QuickLookProps {
  /** The documents of the list Quick Look was opened from, in its order, as far as they're loaded */
  documents: DocumentView[]
  /** How many documents the list has in all; the loaded ones when unknown */
  total?: number
  /** Loads the next page of the list, if it has more: at the end of the loaded rows › asks for it */
  loadMore?: () => void
}

/** The row of a document in the list behind Quick Look, if it's drawn */
function focusRow(docNumber: number) {
  window.document.querySelector<HTMLElement>(`[data-row-key="d${docNumber}"]`)?.focus()
}

/**
 * Runs `then` once React Aria has given the focus back to where it was before the dialog opened, which it does a frame
 * after the dialog goes and which a `focusin` says; or, when there's nothing left to give it to (the details' button
 * the docked pane replaced, a row that isn't drawn), after a short wait. Either way it's after, so it has the last word.
 */
function afterFocusRestored(then: () => void) {
  let done = false
  const finish = () => {
    if (done) return
    done = true
    window.document.removeEventListener('focusin', onFocus)
    clearTimeout(timer)
    then()
  }
  const onFocus = () => queueMicrotask(finish)
  window.document.addEventListener('focusin', onFocus)
  const timer = setTimeout(finish, 300)
}

/**
 * Quick Look (spec section 6.4): the selected document's preview, large over the app. ‹ › and the arrow keys walk the
 * list it was opened from, and the selection walks with them, so the row behind is the one shown; at the end of the
 * loaded rows › loads the next page. Space and Esc close it, and focus is back on the row. Nothing behind it moves.
 */
export function QuickLook(props: QuickLookProps) {
  const { shown } = useQuickLook()
  return shown === undefined ? null : <Shown {...props} docNumber={shown} />
}

function Shown({ documents, total, loadMore, docNumber }: QuickLookProps & { docNumber: number }) {
  const { from, go, close } = useQuickLook()
  const { select } = useSelection()
  const region = useRightRegion()
  const index = documents.findIndex((d) => d.docNumber === docNumber)
  // A document the list doesn't hold (it moved, or Quick Look came from a button of a page with one document): its own
  const own = useDocument(docNumber, documents[index])
  const current = documents[index] ?? own.data

  // At the end of the loaded rows, › asks for the next page and moves on when it comes
  const waitingFor = useRef<number>(undefined)
  const loaded = documents.length
  useEffect(() => {
    if (waitingFor.current !== undefined && loaded > waitingFor.current) {
      const next = documents[waitingFor.current]
      waitingFor.current = undefined
      if (next) show(next.docNumber)
    }
    // show is made each render from what is in the dependencies below
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded])

  function show(next: number) {
    waitingFor.current = undefined
    go(next)
    select(next)
  }

  function leave(dock: boolean) {
    // The focus goes back to where it was (React Aria does), and then to the row Quick Look ended on if that isn't the
    // one it began on, or if it docked: the pane replaced the button it was opened from, and the row is where the
    // arrow keys go on previewing
    const row = docNumber
    close()
    if (dock) region.open('preview')
    if (dock || row !== from) afterFocusRestored(() => focusRow(row))
  }

  const previous = index > 0 ? documents[index - 1] : undefined
  const next = index >= 0 && index < documents.length - 1 ? documents[index + 1] : undefined
  function ask() {
    if (next) show(next.docNumber)
    else if (index >= 0 && loadMore) {
      waitingFor.current = loaded
      loadMore()
    }
  }

  if (!current) return null
  const place = index >= 0 ? t('preview.position', { n: index + 1, total: Math.max(total ?? loaded, loaded) }) : undefined
  return (
    <Overlay
      label={t('preview.quickLook', { name: current.name })}
      isOpen
      onClose={() => leave(false)}
      onPrevious={previous ? () => show(previous.docNumber) : undefined}
      onNext={next || (index >= 0 && loadMore) ? ask : undefined}
      heading={
        <div className={styles.heading}>
          <span className={styles.name}>{current.name}</span>
          <span className={styles.meta}>
            {place && <span>{place}</span>}
            <span>{t('preview.number', { n: current.docNumber })}</span>
          </span>
        </div>
      }
      actions={
        <>
          <Button variant="quiet" aria-label={t('preview.dock')} onPress={() => leave(true)}>
            <Icon name="dock" size={18} />
          </Button>
          <OpenDownload />
        </>
      }
    >
      <PreviewBody document={current} showActions={false} />
    </Overlay>
  )
}

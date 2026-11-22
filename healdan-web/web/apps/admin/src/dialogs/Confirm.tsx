// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useId, useState, type ReactNode } from 'react'
import { Button, Dialog } from '@healdan/ui'
import { t } from '../strings'
import { Refusal } from '../forms/Refusal'
import styles from '../clients/Clients.module.css'

export interface ConfirmProps {
  /** The question, which names what it acts on */
  title: string
  /** The button that does it */
  confirm: string
  /** What the question says of what it affects, which a screen reader reads after the title */
  children: ReactNode
  /** Does it; a refusal it throws stays in the dialog, in the rule's words (P2-R12) */
  run: () => Promise<void>
  /** The dialog closes: Cancel, Esc, a click outside, or once done. The caller stops drawing it. */
  onClose: () => void
}

/**
 * A question before something dangerous (spec 7.2: every dangerous action asks first and names what it affects), as an
 * alert dialog with the warning sign. While it's being done, Cancel is greyed out and Esc and a click outside do nothing,
 * as the server may do it anyway: it closes once done, or stays to say why it wasn't.
 */
export function Confirm({ title, confirm, children, run, onClose }: ConfirmProps) {
  const describedBy = useId()
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<unknown>()

  async function go() {
    setBusy(true)
    setFailure(undefined)
    try {
      await run()
      onClose()
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={title} warning isOpen aria-describedby={describedBy} onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <div className={styles.form}>
          <div id={describedBy} className={styles.form}>
            {children}
          </div>
          <Refusal failure={failure} />
          <div className={styles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button variant="default" onPress={() => void go()} isPending={busy}>
              {confirm}
            </Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}

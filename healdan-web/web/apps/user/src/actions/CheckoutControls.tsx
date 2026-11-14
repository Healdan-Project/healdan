// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useRef } from 'react'
import type { components } from '@healdan/api'
import { Button, t } from '@healdan/ui'
import { checkInAccept, holdSentence } from './checkout'
import { useCheckout } from './useCheckout'
import styles from './actions.module.css'

/**
 * The details panel's check-out: who has the document and since when, and what the person can do about it. Check Out
 * when it's free; Check In and Release for the person's own check-out from the web page. A check-out on a computer, someone
 * else's check-out and a lock are only described: they're released on that computer or in the Classic Interface.
 */
export function CheckoutControls({ document }: { document: components['schemas']['DocumentView'] }) {
  const checkout = useCheckout(document)
  const picker = useRef<HTMLInputElement>(null)
  const sentence = holdSentence(checkout.state)
  return (
    <>
      {sentence && <p className={styles.hold}>{sentence}</p>}
      <div className={styles.holdActions}>
        {checkout.state.kind === 'free' && (
          <Button isDisabled={checkout.busy || !document.canEdit} onPress={() => void checkout.checkOut()}>
            {t('checkout.checkOut')}
          </Button>
        )}
        {checkout.state.kind === 'yours' && (
          <>
            <Button isDisabled={checkout.busy} onPress={() => picker.current?.click()}>
              {t('checkout.checkIn')}
            </Button>
            <Button isDisabled={checkout.busy} onPress={() => void checkout.release()}>
              {t('checkout.release')}
            </Button>
            <input
              ref={picker}
              type="file"
              hidden
              accept={checkInAccept(document.name)}
              aria-label={t('checkout.file')}
              onChange={(event) => {
                const file = event.target.files?.[0]
                event.target.value = ''
                if (file) void checkout.checkIn(file)
              }}
            />
          </>
        )}
      </div>
    </>
  )
}

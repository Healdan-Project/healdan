// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useRef } from 'react'
import type { components } from '@healdan/api'
import { Button, Icon, t } from '@healdan/ui'
import { useCheckout } from './useCheckout'
import styles from './actions.module.css'

/** The toolbar's Check Out for the selected document: only for one that is free and the person may check out. */
export function CheckOutButton({ document }: { document: components['schemas']['DocumentView'] | undefined }) {
  // The hook wants a document, so an empty selection is its own disabled button
  if (!document) return <Placeholder />
  return <ForDocument document={document} />
}

function Placeholder() {
  return (
    <Button isDisabled>
      <Icon name="pencil" size={14} /> {t('checkout.checkOut')}
    </Button>
  )
}

function ForDocument({ document }: { document: components['schemas']['DocumentView'] }) {
  const checkout = useCheckout(document)
  return (
    <Button isDisabled={checkout.busy || checkout.state.kind !== 'free' || !document.canEdit} onPress={() => void checkout.checkOut()}>
      <Icon name="pencil" size={14} /> {t('checkout.checkOut')}
    </Button>
  )
}

/** The document's extension with its dot, which the file picker offers; none for a name without one */
function extensionOf(name: string): string | undefined {
  const dot = name.lastIndexOf('.')
  return dot > 0 && dot < name.length - 1 ? name.slice(dot) : undefined
}

/**
 * The details panel's check-out buttons, under its Check-Out Info (`details/HoldInfo`): Check Out when the document is
 * free; Check In and Release for the person's own check-out from the web page. A check-out on a computer, someone else's
 * check-out and a lock have none, as the web page is no device and never takes a check-out over (R14).
 */
export function CheckoutControls({ document }: { document: components['schemas']['DocumentView'] }) {
  const checkout = useCheckout(document)
  const picker = useRef<HTMLInputElement>(null)
  return (
    <>
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
              accept={extensionOf(document.name)}
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

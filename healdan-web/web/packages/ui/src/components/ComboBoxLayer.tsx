// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useContext, useEffect } from 'react'
import { ComboBoxStateContext, Popover, type PopoverProps } from 'react-aria-components'
import styles from './ComboBoxLayer.module.css'

// React Aria's mark for a layer it leaves shown when an overlay hides the rest of the page (its toasts' region carries it)
const SHOWN_LAYER = 'data-react-aria-top-layer'

/**
 * While its list is open, React Aria's combo box hides everything but the box and the list from screen readers
 * (`ariaHideOutside` in its `useComboBox`), because it draws the list at the end of <body>, where a screen reader would
 * reach it only past the rest of the page. Here the list is drawn right after the box, as WAI-ARIA's combo box examples
 * have it, so there's nothing to hide: the page stays readable while the list is open, a screen reader's cursor can move on
 * from the list to what comes next, and nothing hidden holds a link a keyboard reaches (axe's aria-hidden-focus). React
 * Aria has no switch for its hiding yet (adobe/react-spectrum#4937, where its team means to add one to `useComboBox` for a
 * list that isn't drawn at the end of <body>), so while it looks, the page's layer (the element of <body> that holds the
 * box: the app's root, or a dialog's overlay, whose other fields then stay shown) carries the mark React Aria leaves shown,
 * and it's taken off straight after. A dialog's own hiding of the page behind it is left as it is.
 *
 * React Aria looks once, in an effect of its combo box, as the list opens: what is added to the page later it hides only
 * outside what it left shown then. This runs in an effect of a child of that combo box, which runs first, as a child's
 * effects run before its parent's, and the mark comes off in a microtask, once the pass that runs both effects has ended.
 */
function PageStaysShown({ near }: { near: HTMLElement | null }) {
  const isOpen = useContext(ComboBoxStateContext)?.isOpen ?? false
  useEffect(() => {
    if (!isOpen || near === null) return
    let layer: HTMLElement = near
    while (layer.parentElement !== null && layer.parentElement !== near.ownerDocument.body) layer = layer.parentElement
    if (layer.parentElement === null || layer.hasAttribute(SHOWN_LAYER)) return
    layer.setAttribute(SHOWN_LAYER, '')
    queueMicrotask(() => layer.removeAttribute(SHOWN_LAYER))
  }, [isOpen, near])
  return null
}

export interface ComboBoxPopoverProps extends Omit<PopoverProps, 'UNSTABLE_portalContainer' | 'className'> {
  /** The combo box's `ComboBoxLayer`, once it is drawn */
  layer: HTMLDivElement | null
  className?: string
}

/**
 * A combo box's list (the Popover inside a React Aria ComboBox), drawn in its `ComboBoxLayer`, with the page around the
 * box left shown to a screen reader while it's open (see PageStaysShown). `ComboBox` and `PickField` draw theirs with it.
 */
export function ComboBoxPopover({ layer, className, ...props }: ComboBoxPopoverProps) {
  return (
    <>
      <PageStaysShown near={layer} />
      <Popover {...props} className={className ? `${styles.inLayer} ${className}` : styles.inLayer} UNSTABLE_portalContainer={layer ?? undefined} />
    </>
  )
}

/**
 * Where a combo box's list is drawn: right after React Aria's combo box element in the page, rather than in it, as React
 * Aria draws its children twice (once in a hidden copy that works out the options), so in the caller's landmark, or its
 * dialog. It is as big as the window and fixed over it (`.layer`), so nothing round the box can cut the list off or move
 * it, as at the end of <body>, where React Aria would draw it.
 *
 * ```tsx
 * const [layer, setLayer] = useState<HTMLDivElement | null>(null)
 * <>
 *   <AriaComboBox>…<ComboBoxPopover layer={layer}>…</ComboBoxPopover></AriaComboBox>
 *   <ComboBoxLayer onLayer={setLayer} />
 * </>
 * ```
 */
export function ComboBoxLayer({ onLayer }: { onLayer: (layer: HTMLDivElement | null) => void }) {
  return <div ref={onLayer} className={styles.layer} />
}

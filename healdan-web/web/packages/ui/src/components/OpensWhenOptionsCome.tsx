// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useContext, useEffect } from 'react'
import { ComboBoxStateContext } from 'react-aria-components'

/**
 * React Aria opens a combo box's list when typing changes the box and options exist at that moment, and never later.
 * Options the caller works out afterwards miss that moment: `ComboBox`'s come in the same render as the first character
 * (the caller works them out from it), and `PickField`'s from the server once the typing pauses (`UserField`'s people).
 * So the list is asked to open again when the text or having options changes, only with the box focused: it never opens
 * on its own account. `hasOptions` is the caller's to say: `PickField` says no while the box shows what is picked, so an
 * answer that comes after a pick doesn't open the list again. Drawn inside React Aria's ComboBox, whose state it reads.
 */
export function OpensWhenOptionsCome({ text, hasOptions }: { text: string; hasOptions: boolean }) {
  const state = useContext(ComboBoxStateContext)
  useEffect(() => {
    if (state && state.isFocused && hasOptions && !state.isOpen) state.open(null, 'input')
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only a change of the text or of having options asks
  }, [text, hasOptions])
  return null
}

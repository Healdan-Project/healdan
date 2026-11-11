// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useRef } from 'react'
import { Button, Icon, t, toast } from '@healdan/ui'
import { refusalOf, type Place } from './dropTargets'
import { useFiling } from './FilingProvider'

/** The toolbar's Upload (spec 6.1): picks files for the open folder, which file through the same queue as a drop. */
export function UploadButton({ place }: { place: Place }) {
  const filing = useFiling()
  const picker = useRef<HTMLInputElement>(null)
  return (
    <>
      <Button
        onPress={() => {
          // A matter's top level and a search folder hold no documents: say so, rather than pick files to refuse
          const refusal = refusalOf(place)
          if (refusal) toast(t(refusal))
          else picker.current?.click()
        }}
      >
        <Icon name="upload" size={14} /> {t('filing.upload')}
      </Button>
      <input
        ref={picker}
        type="file"
        multiple
        hidden
        aria-label={t('filing.picker')}
        onChange={(event) => {
          const files = Array.from(event.target.files ?? [])
          // The same file chosen again must still raise a change
          event.target.value = ''
          if (files.length > 0) filing.pick(files, place)
        }}
      />
    </>
  )
}

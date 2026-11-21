// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useBlocker } from '@tanstack/react-router'
import { useCallback, useEffect } from 'react'
import { Confirm } from '../dialogs/Confirm'
import { t } from '../strings'
import type { EditState } from './edits'
import page from '../matters/MatterPage.module.css'

export interface LeavePageQuestionProps {
  /** Where the page's panels stand together: a save on its way, else a change unsaved, else nothing */
  standing: EditState
  /** Whose changes they are, as the question names them: the chosen folder, or the Store */
  name: string
  /**
   * Whether anything is unsaved or being saved at the moment the page would be left, read then, as a report can come
   * before `standing` is drawn
   */
  changing: () => boolean
}

/**
 * Leaving a page over a change (Decision Log 63): a link, the find, the crumb or Back asks first, with Discard Changes
 * over a change unsaved and Leave Page over a save on its way; a change of the page's own address that keeps its path,
 * as a tab's, doesn't ask, and neither does a navigation the page makes with `ignoreBlocker`, as after its own delete. A
 * reload or closing the window over one is asked about by the browser, in its own words.
 */
export function LeavePageQuestion({ standing, name, changing }: LeavePageQuestionProps) {
  const active = standing !== 'clean'
  const shouldBlock = useCallback(({ current, next }: { current: { pathname: string }; next: { pathname: string } }) => current.pathname !== next.pathname && changing(), [changing])
  const blocker = useBlocker({ shouldBlockFn: shouldBlock, enableBeforeUnload: false, disabled: !active, withResolver: true })
  useEffect(() => {
    if (!active) return
    const ask = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', ask)
    return () => window.removeEventListener('beforeunload', ask)
  }, [active])

  if (blocker.status !== 'blocked') return null
  return (
    <Confirm
      title={t('admin.workspace.leaveTitle', { name })}
      confirm={standing === 'saving' ? t('admin.workspace.leavePage') : t('admin.access.discard')}
      run={async () => blocker.proceed()}
      onClose={() => blocker.reset()}
    >
      <p className={page.line}>{t(standing === 'saving' ? 'admin.workspace.leaveSavingLine' : 'admin.workspace.leavePageLine', { name })}</p>
    </Confirm>
  )
}

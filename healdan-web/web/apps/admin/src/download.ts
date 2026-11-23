// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useCallback, useState } from 'react'
import { ApiError } from '@healdan/api'
import { launchDownload, useReportSignedOut } from '@healdan/shell'
import { isAdminsOnly, useReportRefused } from './access'

/** What a page in a file's place said when it held no problem of the API's: no file came, for a reason nobody gave */
const NO_FILE = new Error('No file came in place of the page the server answered')

export interface Download {
  /**
   * The download: `check` first, a small call through the guarded client that the server answers as it would the file
   * (P5-R36), then the file at the address through a hidden frame (`launchDownload`, P5-R38)
   */
  start: (check: () => Promise<unknown>) => Promise<void>
  /** The check is on its way, which the button holds for */
  checking: boolean
  /**
   * What was refused, the check or the file, to say with forms/Refusal; undefined while there's nothing to say, and once
   * the address is another (another log, or other filters), which the refusal wasn't of
   */
  failure: unknown
}

/**
 * A download of the Admin app's, the Server Log's and the Audit Trail's, each said one way. A `401` and a
 * `403 admins-only` are the app's to say: the guarded client marks them for the check, and the frame's answer reports
 * them the same way, so the frame shows Signed Out or the no-access page in place of every screen. Anything else
 * refused, by the check (as a filter the server refuses) or by the server in place of the file (in Chromium and WebKit,
 * which say when the frame loads a page), is the failure, which the page says beside the button.
 */
export function useDownload(href: string): Download {
  const reportSignedOut = useReportSignedOut()
  const reportRefused = useReportRefused()
  const [checking, setChecking] = useState(false)
  const [refused, setRefused] = useState<{ href: string; failure: unknown }>()
  const start = useCallback(
    async (check: () => Promise<unknown>) => {
      setRefused(undefined)
      setChecking(true)
      try {
        await check()
      } catch (error) {
        // The guarded client has told the app of a sign-out or a refusal for anyone but administrators
        if (!(error instanceof ApiError && error.status === 401) && !isAdminsOnly(error)) setRefused({ href, failure: error })
        return
      } finally {
        setChecking(false)
      }
      launchDownload(href, reportSignedOut, (refusal) => {
        if (isAdminsOnly(refusal)) reportRefused()
        else setRefused({ href, failure: refusal ?? NO_FILE })
      })
    },
    [href, reportSignedOut, reportRefused],
  )
  return { start, checking, failure: refused?.href === href ? refused.failure : undefined }
}

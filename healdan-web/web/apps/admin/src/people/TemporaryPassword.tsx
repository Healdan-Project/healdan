// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useState } from 'react'
import type { adminComponents } from '@healdan/api'
import { Button, Icon, toast } from '@healdan/ui'
import { Refusal } from '../forms/Refusal'
import { t } from '../strings'
import styles from './People.module.css'

type A = adminComponents['schemas']

/**
 * The password, with Copy. A copy the browser refuses (no permission, or the page not in front) says so beside it, and
 * the password stays to be written down; it never goes into what's said.
 */
function PasswordText({ password }: { password: string }) {
  const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard
  const [refused, setRefused] = useState(false)

  async function copy(to: Clipboard) {
    setRefused(false)
    try {
      await to.writeText(password)
      toast(t('admin.password.copied'))
    } catch {
      setRefused(true)
    }
  }

  return (
    <>
      <div className={styles.row}>
        <p role="group" aria-label={t('admin.password.label')} className={styles.password}>
          {password}
        </p>
        {clipboard !== undefined && (
          // The dialog's first stop, so Enter never closes it, and its password, unseen
          <Button autoFocus onPress={() => void copy(clipboard)}>
            <span className={styles.row}>
              <Icon name="copy" size={14} />
              {t('admin.password.copy')}
            </span>
          </Button>
        )}
      </div>
      {refused && <Refusal>{t('admin.password.copyFailed')}</Refusal>}
    </>
  )
}

/** Why a temporary password the person was to be emailed wasn't: as far as the app can tell, else the mail server */
function whyNotEmailed(person: A['User'], options: A['UserOptions'] | undefined): string {
  if (options?.outgoingEmail === false) return t('admin.password.reasonNotSetUp')
  if (person.email === null) return t('admin.password.reasonNoEmail')
  return t('admin.password.reasonTimedOut')
}

export interface PasswordShownProps {
  person: A['User']
  /** What the create or the reset answered: the password, or that it was emailed */
  answer: Pick<A['TemporaryPassword'], 'temporaryPassword' | 'emailed'>
  /** Whether it was to be emailed, so a password shown says why it wasn't */
  asked: boolean
  options: A['UserOptions'] | undefined
}

/**
 * A temporary password just made (P4-R7): that it was emailed, and to where, with nothing shown; or the password itself,
 * once, with Copy, and why it wasn't emailed when it was to be; or, with no password and not emailed, that there's
 * none to show. It lives only in the dialog's state that holds the answer,
 * and goes as the dialog closes: never in the query cache, the address or the browser's storage.
 */
export function PasswordShown({ person, answer, asked, options }: PasswordShownProps) {
  // Emailed only when the answer says so: a password missing from an answer that wasn't emailed is said as missing
  if (answer.emailed) return <p className={styles.line}>{t('admin.password.emailed', { name: person.name, email: person.email ?? '' })}</p>
  if (answer.temporaryPassword === null) return <p className={styles.line}>{t('admin.password.missing', { name: person.name })}</p>
  return (
    <>
      <p className={styles.line}>
        {asked ? t('admin.password.notEmailed', { reason: whyNotEmailed(person, options), name: person.name }) : t('admin.password.give', { name: person.name })}
      </p>
      <PasswordText password={answer.temporaryPassword} />
      <p className={styles.note}>{t('admin.password.onlyHere')}</p>
    </>
  )
}

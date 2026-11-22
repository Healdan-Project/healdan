// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { apiPath, type adminComponents } from '@healdan/api'
import { useApi } from '@healdan/shell'
import { Button, CheckField, Dialog } from '@healdan/ui'
import { Refusal } from '../forms/Refusal'
import { t } from '../strings'
import formStyles from '../forms/Form.module.css'
import { personChanged, useUserOptions } from './queries'
import { PasswordShown } from './TemporaryPassword'
import styles from './People.module.css'

type A = adminComponents['schemas']

/** A reset's answer, held only while the dialog shows it */
interface Reset {
  answer: A['TemporaryPassword']
  asked: boolean
}

/**
 * Reset Password (spec 8.7; P4-R8), for a password account: it asks first, saying the person gets a temporary password
 * they must change at their next sign-in and that they're signed out everywhere, and offers to email it while outgoing
 * email is set up and they have an email address. Then the temporary password is shown once, with Copy, or the dialog says
 * it was emailed; it goes as the dialog closes (P4-R7), which only Done closes, as that's its only showing (P4-R38). The
 * page isn't offered it for an account linked to a sign-in provider. While the reset is on its way the dialog holds, and
 * a refusal stays in it, in the rule's words.
 */
export function ResetPasswordDialog({ person, onClose }: { person: A['User']; onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const describedBy = useId()
  const options = useUserOptions().data
  const [emailTicked, setEmailTicked] = useState(false)
  const [failure, setFailure] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const [reset, setReset] = useState<Reset>()
  const canEmail = options?.outgoingEmail === true && person.email !== null

  async function run() {
    setBusy(true)
    setFailure(undefined)
    const asked = canEmail && emailTicked
    try {
      const answer = await api.send<A['TemporaryPassword']>('POST', apiPath`/v1/admin/users/${person.id}/reset-password`, { emailPassword: asked } satisfies A['PasswordReset'])
      // Their password's date, and their devices, which the reset signed out
      personChanged(queryClient, person.id, { devices: true })
      setReset({ answer, asked })
    } catch (error) {
      setFailure(error)
    } finally {
      setBusy(false)
    }
  }

  if (reset !== undefined) {
    return (
      // The password goes with the dialog, which only Done closes: Esc and a click outside do nothing, as this is its only
      // showing (P4-R38)
      <Dialog title={t('admin.resetPassword.doneTitle', { name: person.name })} isOpen onOpenChange={() => undefined}>
        <div className={formStyles.form}>
          <PasswordShown person={person} answer={reset.answer} asked={reset.asked} options={options} />
          <div className={formStyles.buttons}>
            {/* The question went as this came, so the focus is put inside: on Copy, or here when nothing is shown */}
            <Button variant="default" autoFocus={reset.answer.temporaryPassword === null} onPress={onClose}>
              {t('admin.password.done')}
            </Button>
          </div>
        </div>
      </Dialog>
    )
  }

  return (
    <Dialog title={t('admin.resetPassword.title', { name: person.name })} warning isOpen aria-describedby={describedBy} onOpenChange={(open) => !open && !busy && onClose()}>
      {(close) => (
        <div className={formStyles.form}>
          <div id={describedBy} className={formStyles.form}>
            <p className={styles.line}>{t('admin.resetPassword.line')}</p>
            <p className={styles.line}>{t('admin.resetPassword.signsOut')}</p>
          </div>
          {options !== undefined &&
            (canEmail ? (
              <CheckField label={t('admin.password.email')} description={t('admin.password.emailTo', { email: person.email ?? '' })} checked={emailTicked} onChange={setEmailTicked} />
            ) : (
              <p className={formStyles.note}>{t(options.outgoingEmail ? 'admin.password.noEmail' : 'admin.password.notSetUp')}</p>
            ))}
          <Refusal failure={failure} />
          <div className={formStyles.buttons}>
            <Button onPress={close} isDisabled={busy}>
              {t('common.cancel')}
            </Button>
            <Button variant="default" onPress={() => void run()} isPending={busy}>
              {t('admin.resetPassword.confirm')}
            </Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}

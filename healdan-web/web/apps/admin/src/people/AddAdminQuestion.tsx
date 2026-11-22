// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { Confirm } from '../dialogs/Confirm'
import { t } from '../strings'
import styles from './People.module.css'

/**
 * The question before someone is added to `admin` (spec 5, 8.7), wherever they're added, asked as the change is sent
 * (Save, Create User): its members use Healdan Admin and read every document, so it says so, and advises an everyday
 * account apart for their own work. `onConfirm` sends the change, once the question has gone.
 */
export function AddAdminQuestion({ name, onConfirm, onClose }: { name: string; onConfirm: () => void; onClose: () => void }) {
  return (
    <Confirm title={t('admin.addAdmin.title', { name })} confirm={t('admin.addAdmin.confirm')} run={async () => onConfirm()} onClose={onClose}>
      <p className={styles.line}>{t('admin.addAdmin.line')}</p>
      <p className={styles.line}>{t('admin.addAdmin.everyday')}</p>
    </Confirm>
  )
}

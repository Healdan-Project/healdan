// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'

/** What every view of a document's preview takes */
export interface PreviewProps {
  document: components['schemas']['DocumentView']
  /** A version of the document, such as 1.0; the current file by default */
  version?: string
  /** Whether a view that can't show the file offers Open and Download itself; Quick Look's bar has them already. Yes by default. */
  showActions?: boolean
}

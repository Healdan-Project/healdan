// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { destroyAnnouncer } from 'react-aria/private/live-announcer/LiveAnnouncer'

/**
 * Takes away React Aria's live region, for the tests' cleanup. React Aria makes the region at its first announcement
 * (a table's selection, a combo box's options) and then announces 100 ms later, outside React's act environment, which
 * Vitest without globals never sets. A test file that ends sooner leaves that timer to run once its page has gone, and
 * the run fails on `document is not defined` though every test passed.
 */
export function resetAnnouncer(): void {
  destroyAnnouncer()
}

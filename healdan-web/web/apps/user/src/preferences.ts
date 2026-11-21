// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { preferences } from '@healdan/shell'

// The User app's preferences keep the prefix they have always had, so what a browser kept before is read as it was.
export const { readNumber, keepNumber, forget, readJson, keepJson } = preferences('healdan.')

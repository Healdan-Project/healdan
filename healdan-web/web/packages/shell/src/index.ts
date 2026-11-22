// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// What every app of the workspace needs that asks the server or draws the frame's shared parts. It registers no router
// and uses no typed Link: each app registers its own router, which one program can't hold twice.

export { ServicesProvider, useApi, useReportSignedOut, useSignedOut } from './services'
export { createSignedOut, type SignedOutState } from './signedOut'
export { ASK_ON_RETURN, createQueryClient, listenForReturn, type QueryClientOptions } from './queryClient'
export type { PageOf } from './pages'
export { preferences, type Preferences } from './preferences'
export { parseSearch, stringifySearch } from './search'
export { fetchSubfolders, idFrom, shellKeys, useFolder, useMe, useRecentMatters } from './queries'
export { fieldErrors, isStorageUnreachable, messageFor } from './frame/errors'
export { FRAME_KEPT, launchDownload } from './download'
export { MessagesProvider, useMessageFor } from './frame/messages'
export { NARROW, focusInto, isInnermost, openLayer, useNarrow } from './frame/narrow'
export { Mark } from './frame/Mark'
export { SignedOut } from './frame/SignedOut'
export { InsecureAddress } from './frame/InsecureAddress'
export { NoAdminAccess } from './frame/NoAdminAccess'
export { ErrorPanel } from './frame/ErrorPanel'
export { goTo } from './frame/navigate'
export { PersonMenu } from './frame/PersonMenu'
export { HelpMenu } from './frame/HelpMenu'

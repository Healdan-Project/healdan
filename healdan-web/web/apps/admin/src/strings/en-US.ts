// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { PluralForms } from '@healdan/ui'

// The Admin app's own strings, over @healdan/ui's shared catalogue, so the User app never downloads them. US English;
// labels in Title Case. Every key starts admin., and {matter}, {matters} and {matterLower} are the firm's words, as in
// the shared catalogue.
export const adminEnUS = {
  'admin.app.name': 'Healdan Admin',
  // The tag beside Healdan in the top bar
  'admin.app.tag': 'Admin',
  // scripts/check-dist.mjs looks for this key in the User app's build, which never holds the Admin app's code
  'admin.dashboard.title': 'Dashboard',
  'admin.dashboard.updated': 'Updated {when}',
  'admin.dashboard.refresh': 'Refresh',

  'admin.frame.openHealdan': 'Open Healdan',
  'admin.help.guide': 'Admin Guide',

  // The left pane's groups (spec 7.1), each drawn once one of its screens has shipped
  'admin.nav.firm': 'Firm',
  'admin.nav.people': 'People',
  'admin.nav.storage': 'Storage',
  'admin.nav.search': 'Search',
  'admin.nav.monitoring': 'Monitoring',
  // The screens, each under its group
  'admin.nav.clients': 'Clients',
  'admin.nav.matters': '{matters}',

  // The top bar's find: its name and placeholder, as the spec words it (7.1)
  'admin.find.label': 'Find a client, matter or person',
  'admin.find.clients': 'Clients',
  'admin.find.matters': '{matters}',
  'admin.find.people': 'People',
  'admin.find.client': '{number} – {name}',
  'admin.find.disabled': 'Disabled',
  'admin.find.none': 'Nothing found.',

  'admin.notFound.title': 'Page Not Found',
  'admin.notFound.message': "There's no such page in Healdan Admin.",
  'admin.notFound.dashboard': 'Go to the Dashboard',

  'admin.attention.title': 'Needs Attention',
  'admin.attention.none': 'Nothing needs your attention.',
  // A row's link where the server's address is none of the app's screens'
  'admin.attention.open': 'Open',
  // What the server couldn't check this time, by the labels below: "Couldn't check Stuck Files, Errors."
  'admin.attention.unchecked': "Couldn't check {what}.",
  'admin.attention.label.files-stuck': 'Stuck Files',
  'admin.attention.label.backend-unreachable': 'Storage Backends',
  'admin.attention.label.index-failed': 'Search Index',
  'admin.attention.label.errors': 'Errors',
  'admin.attention.label.previews-given-up': 'Previews',
  'admin.attention.label.job-failed': 'Failed Jobs',
  'admin.attention.label.job-overdue': 'Overdue Jobs',
  'admin.attention.label.disk-low': 'Disk Space',

  'admin.health.title': 'Server Health',
  'admin.health.server': 'Server',
  'admin.health.database': 'Database',
  'admin.health.disk': 'Disk',
  'admin.health.memory': 'Memory',
  'admin.health.sign-in': 'Sign-In',
  'admin.health.storage-backends': 'Storage Backends',
  'admin.health.search-index': 'Search Index',
  'admin.health.previews': 'Previews',
  'admin.health.background-jobs': 'Background Jobs',
  'admin.health.outgoing-email': 'Outgoing Email',
  // A tile's status, in words beside its dot
  'admin.status.ok': 'OK',
  'admin.status.attention': 'Needs Attention',
  'admin.status.off': 'Off',
  // A tile whose part the server couldn't read
  'admin.status.unavailable': 'Unavailable',

  'admin.usage.title': 'Usage',
  'admin.usage.totals': 'Totals',
  'admin.usage.asOf': 'As of {when}',
  'admin.usage.pending': "Usage hasn't been worked out since the server started. It shows here within a few minutes.",
  'admin.usage.failed': "Usage couldn't be worked out.",
  'admin.usage.failedSince': "Usage couldn't be worked out since {when}. These are the figures from then.",
  'admin.usage.documents': 'Documents',
  'admin.usage.storage': 'Stored',
  'admin.usage.matters': '{matters}',
  'admin.usage.clients': 'Clients',
  'admin.usage.people': 'People',
  'admin.usage.sizeLast7': '{size} in the last 7 days',
  'admin.usage.filedChart': 'Documents Filed, Last 30 Days',
  'admin.usage.day': 'Day',
  'admin.usage.today': 'Today',
  'admin.usage.stores': 'Storage by Store',
  'admin.usage.storeOn': '{size} on {backend}',
  'admin.usage.storeOnEncrypted': '{size} on {backend}, encrypted',
  'admin.usage.busiest': 'Busiest {matters} This Week',
  'admin.usage.noneBusy': 'Nothing was filed in the last 7 days.',

  // The pickers: a client is offered and shown by its number and name
  'admin.client.field': 'Client',
  'admin.client.label': '{number} – {name}',

  // A page's way back to its list, above its title
  'admin.crumbs.label': 'Breadcrumb',

  // The Clients list (spec 8.2): its words, its columns and what it says when it has no rows
  'admin.clients.title': 'Clients',
  'admin.clients.new': 'New Client',
  'admin.clients.filters': 'Filter Clients',
  'admin.clients.text': 'Number or Name',
  'admin.clients.column.number': 'Number',
  'admin.clients.column.name': 'Name',
  'admin.clients.column.matters': '{matters}',
  'admin.clients.column.documents': 'Documents',
  'admin.clients.column.lastActivity': 'Last Activity',
  'admin.clients.figuresAt': 'Documents and Last Activity as of {when}',
  'admin.clients.figuresPending': "Documents and Last Activity haven't been worked out since the server started. They show here within a few minutes.",
  'admin.clients.none': 'No client has been made yet.',
  'admin.clients.noneMatch': 'No client matches the filter.',

  // A client's page (spec 8.2): its facts, its matters, and what it says when there's no such client
  'admin.client.rename': 'Rename',
  'admin.client.number': 'Number',
  'admin.client.matters': '{matters}',
  'admin.client.documents': 'Documents',
  'admin.client.lastActivity': 'Last Activity',
  // A figure the summary hasn't given since the server started
  'admin.client.pending': 'Not worked out yet',
  // The last activity of a client with nothing filed
  'admin.client.noActivity': 'None',
  // The heading of the client's matters, under its facts
  'admin.client.mattersTitle': '{matters}',
  'admin.client.noMatters': 'No {matterLower} has been made for this client yet.',
  'admin.client.notFound.title': 'Client Not Found',
  'admin.client.notFound.message': "There's no such client.",
  'admin.client.notFound.clients': 'Go to Clients',

  // New Client, and renaming one: the number is set once, and only the name changes afterwards (spec 8.2)
  'admin.clientDialog.newTitle': 'New Client',
  'admin.clientDialog.renameTitle': 'Rename Client',
  'admin.clientDialog.number': 'Number',
  'admin.clientDialog.name': 'Name',
  'admin.clientDialog.create': 'Create Client',
  'admin.clientDialog.rename': 'Rename',
  'admin.clientDialog.numberNote': "A client's number can't be changed once the client is made.",
  'admin.clientDialog.numberFixed': "Its number, {number}, can't be changed.",
  'admin.clientDialog.numberInvalid': 'The number has to be a whole number, 1 or more.',
  'admin.clientDialog.numberUsed': 'Client number {number} is already used, by {name}.',
  // Once a client is made: its number and name
  'admin.clientDialog.created': 'Created {client}',

  // The Matters list (spec 8.3): its filters, its columns and what it says when it has no rows
  'admin.matters.title': '{matters}',
  'admin.matters.filters': 'Filter {matters}',
  'admin.matters.text': 'Number or Name',
  'admin.matters.store': 'Store',
  'admin.matters.allStores': 'All Stores',
  'admin.matters.owner': 'Owner',
  'admin.matters.clear': 'Clear Filters',
  'admin.matters.column.number': '{matter}',
  'admin.matters.column.name': 'Name',
  'admin.matters.column.client': 'Client',
  'admin.matters.column.store': 'Store',
  'admin.matters.column.owner': 'Owner',
  'admin.matters.column.template': 'Template',
  'admin.matters.column.documents': 'Documents',
  'admin.matters.column.bytes': 'Size',
  'admin.matters.column.lastActivity': 'Last Activity',
  'admin.matters.figuresAt': 'Documents, Size and Last Activity as of {when}',
  'admin.matters.figuresPending': "Documents, Size and Last Activity haven't been worked out since the server started. They show here within a few minutes.",
  'admin.matters.none': 'No {matterLower} has been made yet.',
  'admin.matters.noneMatch': 'No {matterLower} matches the filters.',
} as const satisfies Record<`admin.${string}`, string>

export const adminPlurals = {
  // Needs Attention's rows, by the item's code and count; the server's detail follows
  'admin.attention.files-stuck': { one: '{n} file is stuck moving to storage', other: '{n} files are stuck moving to storage' },
  'admin.attention.backend-unreachable': { one: "{n} storage backend can't be reached", other: "{n} storage backends can't be reached" },
  'admin.attention.index-failed': {
    one: "{n} document couldn't be indexed, so search won't find its text",
    other: "{n} documents couldn't be indexed, so search won't find their text",
  },
  'admin.attention.errors': { one: '{n} unresolved error in the last 24 hours', other: '{n} unresolved errors in the last 24 hours' },
  'admin.attention.previews-given-up': { one: '{n} preview was given up on', other: '{n} previews were given up on' },
  'admin.attention.job-failed': { one: '{n} background job failed when it last ran', other: '{n} background jobs failed when they last ran' },
  'admin.attention.job-overdue': { one: "{n} background job hasn't run when it should have", other: "{n} background jobs haven't run when they should have" },
  'admin.attention.disk-low': { one: '{n} disk is more than 85% full', other: '{n} disks are more than 85% full' },
  // A left pane entry's name, with its count: "Errors, 14 need attention"
  'admin.nav.count': { one: '{label}, {n} needs attention', other: '{label}, {n} need attention' },
  'admin.usage.last7': { one: '{n} in the last 7 days', other: '{n} in the last 7 days' },
  'admin.usage.last30': { one: '{n} in the last 30 days', other: '{n} in the last 30 days' },
  'admin.usage.signedIn': { one: '{n} signed in now', other: '{n} signed in now' },
  'admin.usage.filed': { one: '{n} filed', other: '{n} filed' },
  // Beside the Clients list's title
  'admin.clients.count': { one: '{n} Client', other: '{n} Clients' },
} as const satisfies Record<`admin.${string}`, PluralForms>

export type AdminStringKey = keyof typeof adminEnUS
export type AdminPluralKey = keyof typeof adminPlurals

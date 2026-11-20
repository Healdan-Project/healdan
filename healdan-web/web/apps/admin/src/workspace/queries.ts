// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useQuery, type QueryClient } from '@tanstack/react-query'
import { apiPath, type adminComponents, type components } from '@healdan/api'
import { ASK_ON_RETURN, useApi } from '@healdan/shell'
import { adminKey } from '../queries'

type A = adminComponents['schemas']

/**
 * The keys of what a matter's page, the Workspace Editor and the templates' screens read, each under the admin route's
 * own segments (adminKey), so `['admin', 'matters']` covers a matter's page as it covers the lists, and
 * `['admin', 'templates', id]` a template and its problems.
 */
export const workspaceKeys = {
  matter: (id: number) => [...adminKey('/v1/admin/matters'), id] as const,
  templates: adminKey('/v1/admin/templates'),
  template: (id: number) => [...adminKey('/v1/admin/templates'), id] as const,
  problems: (id: number) => [...adminKey('/v1/admin/templates'), id, 'problems'] as const,
  /** Every workspace's tree */
  workspaces: ['admin', 'workspaces'] as const,
  tree: (folderId: number) => ['admin', 'workspaces', folderId, 'tree'] as const,
  /** Every folder's access and values */
  folders: ['admin', 'folders'] as const,
  access: (id: number) => ['admin', 'folders', id, 'access'] as const,
  properties: (id: number) => ['admin', 'folders', id, 'properties'] as const,
  searchFolder: (id: number) => ['admin', 'search-folders', id] as const,
  /** What a folder holds, through the public route a delete's question uses */
  contents: (id: number) => ['folders', id, 'contents'] as const,
}

/** A read of the Admin app's that asks again each time the window comes back, however fresh it is (spec 7.2) */
function useRead<T>(key: readonly unknown[], path: string, enabled = true) {
  const api = useApi()
  return useQuery({ queryKey: key, queryFn: ({ signal }) => api.get<T>(path, undefined, { signal }), enabled, meta: ASK_ON_RETURN })
}

/** A matter, for its page; nothing is asked without an id */
export function useMatter(id: number) {
  return useRead<A['Matter']>(workspaceKeys.matter(id), apiPath`/v1/admin/matters/${id}`)
}

/** Every workspace template, by name: a short list, as Stores are (spec 7.2), for its screens and New Matter's menu */
export function useTemplates() {
  return useRead<A['TemplateInfo'][]>(workspaceKeys.templates, '/v1/admin/templates')
}

export function useTemplate(id: number) {
  return useRead<A['TemplateInfo']>(workspaceKeys.template(id), apiPath`/v1/admin/templates/${id}`)
}

/** What New Matter would refuse in a matter made from the template, which its page shows as warnings */
export function useTemplateProblems(id: number, enabled = true) {
  return useRead<A['TemplateProblem'][]>(workspaceKeys.problems(id), apiPath`/v1/admin/templates/${id}/problems`, enabled)
}

/** A workspace's folders, from its top folder: a matter's own, or a template's; nothing is asked without one */
export function useWorkspaceTree(folderId: number | undefined) {
  return useRead<A['WorkspaceNode']>(workspaceKeys.tree(folderId ?? -1), apiPath`/v1/admin/workspaces/${folderId ?? -1}/tree`, folderId !== undefined)
}

export function useFolderAccess(id: number) {
  return useRead<A['FolderAccess']>(workspaceKeys.access(id), apiPath`/v1/admin/folders/${id}/access`)
}

export function useFolderProperties(id: number, enabled = true) {
  return useRead<A['FolderProperties']>(workspaceKeys.properties(id), apiPath`/v1/admin/folders/${id}/properties`, enabled)
}

export function useSearchFolder(id: number) {
  return useRead<A['SearchFolder']>(workspaceKeys.searchFolder(id), apiPath`/v1/admin/search-folders/${id}`)
}

/** What a folder holds, counted afresh each time a question asks (an administrator's counts take everything) */
export function useFolderContents(id: number) {
  const api = useApi()
  return useQuery({
    queryKey: workspaceKeys.contents(id),
    queryFn: ({ signal }) => api.get<components['schemas']['TrashContents']>(apiPath`/v1/folders/${id}/contents`, undefined, { signal }),
    staleTime: 0,
    gcTime: 0,
  })
}

/**
 * Asks again for what a change in a workspace may have changed: its tree, every folder's access and values (a folder's
 * change reaches those below it), and, in a template, the template and its problems. `kept` is the answer just put in
 * place, such as the access a save answered with, which isn't asked for again.
 */
export function workspaceChanged(queryClient: QueryClient, rootFolderId: number, kind: 'matter' | 'template', kept?: readonly unknown[]) {
  const same = (key: readonly unknown[]) => kept !== undefined && key.length === kept.length && key.every((part, i) => part === kept[i])
  void queryClient.invalidateQueries({ queryKey: workspaceKeys.tree(rootFolderId) })
  void queryClient.invalidateQueries({ queryKey: workspaceKeys.folders, predicate: ({ queryKey }) => !same(queryKey) })
  void queryClient.invalidateQueries({ queryKey: ['admin', 'search-folders'], predicate: ({ queryKey }) => !same(queryKey) })
  if (kind === 'template') void queryClient.invalidateQueries({ queryKey: workspaceKeys.templates, predicate: ({ queryKey }) => !same(queryKey) })
}

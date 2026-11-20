// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { adminComponents } from '@healdan/api'

export type WorkspaceNode = adminComponents['schemas']['WorkspaceNode']

/** A node of the tree with the names of the folders above it, from the workspace's top, itself last */
export interface Placed {
  node: WorkspaceNode
  path: string[]
}

/** Every node of the tree, its own top first, in the tree's order */
export function placed(root: WorkspaceNode): Placed[] {
  const all: Placed[] = []
  const walk = (node: WorkspaceNode, above: string[]) => {
    const path = [...above, node.name]
    all.push({ node, path })
    node.children.forEach((child) => walk(child, path))
  }
  walk(root, [])
  return all
}

export function findNode(root: WorkspaceNode, id: number): WorkspaceNode | undefined {
  return placed(root).find((one) => one.node.id === id)?.node
}

/** The folder a node is in, or undefined for the top */
export function parentOf(root: WorkspaceNode, id: number): WorkspaceNode | undefined {
  return placed(root).find((one) => one.node.children.some((child) => child.id === id))?.node
}

/** Everything below a node, at every level */
export function below(node: WorkspaceNode): WorkspaceNode[] {
  return node.children.flatMap((child) => [child, ...below(child)])
}

/** The ids of the nodes that hold something, which the tree opens at first */
export function holding(root: WorkspaceNode): Set<string> {
  return new Set(placed(root).filter((one) => one.node.children.length > 0).map((one) => String(one.node.id)))
}

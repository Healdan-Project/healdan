// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import styles from './Table.module.css'

/**
 * A heading's name, where it has the heading's keyboard focus: on a list whose columns resize, React Aria's heading focuses
 * its first focusable child, which is its name, before its edge's slider. None where the heading has its focus itself.
 */
export const nameOf = (heading: Element) => heading.querySelector<HTMLElement>(`.${styles.heading}[tabindex]`)

/**
 * Gives a heading the focus: the heading itself first, whose focus makes it the list's own (React Aria's grid goes on from it
 * by the arrows; its name's focus alone doesn't, while the focus shows), then its name where that has the heading's focus.
 */
export function focusHeading(heading: HTMLElement) {
  heading.focus()
  nameOf(heading)?.focus()
}

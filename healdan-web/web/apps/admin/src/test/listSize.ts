// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// jsdom lays nothing out, and React Aria's virtualiser, with no size to go by under test, then draws every row a list
// holds, and every cell of them again each time a row is added: a list of a few hundred rows takes seconds to page.
// Given a size, it draws only the rows in view, as it does in a browser. React Aria takes the size from clientWidth and
// clientHeight once HTMLElement's prototype has both, so every element has them until resetListSize().

/** Gives every list a size in pixels, before a render, so it draws only the rows in view. */
export function setListSize(width: number, height: number): void {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => width })
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, get: () => height })
}

/** Back to jsdom's own, with no size, where a list draws every row it holds */
export function resetListSize(): void {
  Reflect.deleteProperty(HTMLElement.prototype, 'clientWidth')
  Reflect.deleteProperty(HTMLElement.prototype, 'clientHeight')
}

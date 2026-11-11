// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// jsdom has no IntersectionObserver, which React Aria's load-more rows use to see the end of a list coming into view.
// This one sees nothing until a test calls bringObservedIntoView(), as scrolling to the end would.

const live = new Set<FakeIntersectionObserver>()

class FakeIntersectionObserver implements IntersectionObserver {
  readonly root: Element | Document | null
  readonly rootMargin: string
  readonly thresholds: readonly number[] = [0]
  readonly scrollMargin = '0px'
  private readonly targets = new Set<Element>()

  constructor(
    private readonly callback: IntersectionObserverCallback,
    options: IntersectionObserverInit = {},
  ) {
    this.root = options.root ?? null
    this.rootMargin = options.rootMargin ?? '0px'
    live.add(this)
  }

  observe(target: Element): void {
    this.targets.add(target)
  }

  unobserve(target: Element): void {
    this.targets.delete(target)
  }

  disconnect(): void {
    this.targets.clear()
    live.delete(this)
  }

  takeRecords(): IntersectionObserverEntry[] {
    return []
  }

  intersect(): void {
    const entries = [...this.targets].map((target) => {
      const rect = target.getBoundingClientRect()
      return {
        target,
        isIntersecting: true,
        intersectionRatio: 1,
        boundingClientRect: rect,
        intersectionRect: rect,
        rootBounds: null,
        time: 0,
      } satisfies IntersectionObserverEntry
    })
    if (entries.length > 0) this.callback(entries, this)
  }
}

export function installIntersectionObserver(): void {
  globalThis.IntersectionObserver = FakeIntersectionObserver
}

/** Tells every observer that what it watches is in view. Wrap it in act(), as it may change state. */
export function bringObservedIntoView(): void {
  for (const observer of [...live]) observer.intersect()
}

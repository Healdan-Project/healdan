// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { useNavigate } from '@tanstack/react-router'
import { useCallback, type AnchorHTMLAttributes, type MouseEvent } from 'react'
import { goTo } from '@healdan/shell'
import { appAddressOf } from '../addresses'

/**
 * Opens a link: a screen of this app inside it, with the router, so the page isn't loaded again; anything else, the Classic
 * Interface for one, with a full page load.
 */
export function useFollow(): (link: string) => void {
  const navigate = useNavigate()
  return useCallback(
    (link: string) => {
      const address = appAddressOf(link)
      if (address === undefined) goTo(link)
      else void navigate({ href: address })
    },
    [navigate],
  )
}

/** A click the browser itself should take: with a modifier or another button, a new tab or window; or one already handled */
function browsersOwn(event: MouseEvent<HTMLAnchorElement>, target: string | undefined): boolean {
  return event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || (target !== undefined && target !== '_self')
}

/**
 * A link a server answer gives (spec 8.1: the Dashboard's rows and tiles), which can name a screen of this app or the Classic
 * Interface. Its address stays the link's, so a new tab or a copied link opens it; a plain click on a screen of the app
 * opens it with the router, as the left pane's links do, and on anything else is the browser's.
 */
export function ServerLink({ href, onClick, target, ...rest }: AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) {
  const navigate = useNavigate()
  const address = appAddressOf(href)
  return (
    <a
      {...rest}
      href={href}
      target={target}
      onClick={(event) => {
        onClick?.(event)
        if (address === undefined || browsersOwn(event, target)) return
        event.preventDefault()
        void navigate({ href: address })
      }}
    />
  )
}

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

// Healdan's mark, the docs site's favicon drawn inline.
export function Mark({ size = 22 }: { size?: number }) {
  return (
    <svg viewBox="0 0 260 260" width={size} height={size} aria-hidden="true" focusable="false">
      <g transform="translate(22,5)">
        <defs>
          <clipPath id="hd-mark-0">
            <polygon points="34,59.65 135,93.69 135,210.35 34,172.31" />
          </clipPath>
          <clipPath id="hd-mark-1">
            <polygon points="58,43.65 159,77.69 159,194.35 58,156.31" />
          </clipPath>
        </defs>
        <path d="M108,0 L0,30 L0,140 C0,200 50,232 108,250 Z" fill="#0b5dab" />
        <path d="M108,0 L216,30 L216,140 C216,200 166,232 108,250 Z" fill="#22a2e0" />
        <polygon points="88,36 177,66 177,170 88,136" fill="#ffffff" />
        <g clipPath="url(#hd-mark-1)">
          <path d="M108,0 L0,30 L0,140 C0,200 50,232 108,250 Z" fill="#0b5dab" />
          <path d="M108,0 L216,30 L216,140 C216,200 166,232 108,250 Z" fill="#22a2e0" />
        </g>
        <polygon points="64,52 153,82 153,186 64,152" fill="#ffffff" />
        <g clipPath="url(#hd-mark-0)">
          <path d="M108,0 L0,30 L0,140 C0,200 50,232 108,250 Z" fill="#0b5dab" />
          <path d="M108,0 L216,30 L216,140 C216,200 166,232 108,250 Z" fill="#22a2e0" />
        </g>
        <polygon points="40,68 129,98 129,202 40,168" fill="#ffffff" />
      </g>
    </svg>
  )
}

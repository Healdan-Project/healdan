// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { ReactNode } from 'react'
import { MenuSection } from 'react-aria-components'
import { Icon } from '../icons'
import { t } from '../strings'
import { MenuItem, MenuSeparator } from './Menu'
import styles from './Menu.module.css'
import type { TableColumn, TableColumnChoice, TableRow, TableSort } from './Table'

/** What a heading's menu can offer: each only where the list switches it on */
export interface HeadingMenuOptions {
  sort: TableSort | undefined
  onSort: ((sort: TableSort) => void) | undefined
  relevance: { onSelect: () => void } | undefined
  /** Starts a resize of a column by the keyboard; undefined while the headings draw no edges */
  startResize: ((column: string) => void) | undefined
  choice: TableColumnChoice | undefined
  /** Choose Columns…: opens the Columns panel */
  onChoose: () => void
  onResetColumns: (() => void) | undefined
}

/** A sort, ticked while it's the list's: a screen reader hears it as the checked one of the sorts */
function SortItem({ id, label, onAction }: { id: string; label: string; onAction: () => void }) {
  return (
    <MenuItem id={id} textValue={label} onAction={onAction}>
      {({ isSelected }) => (
        <>
          {label}
          {isSelected && <Icon name="check" className={styles.check} />}
        </>
      )}
    </MenuItem>
  )
}

/**
 * A heading's menu (spec §4.4): its sorts, Resize Column, the moves and Hide, Choose Columns… and Reset Columns, in groups.
 * Title, the row header, is first and stays there, so it never moves or hides, and no column moves before it. Undefined when
 * it would offer nothing, which leaves the browser's own menu on the heading.
 */
export function headingMenu<T extends TableRow>(columns: TableColumn<T>[], id: string, options: HeadingMenuOptions): { label: string; items: ReactNode } | undefined {
  const index = columns.findIndex((column) => column.id === id)
  const column = columns[index]
  if (!column) return undefined
  const { sort, onSort, relevance, startResize, choice, onResetColumns } = options

  // The column's sorts, one of them ticked while it's the list's; then Sort by Relevance, a plain item, as it shows only while
  // another order is the list's and so would be a choice never checked
  const sorts: ReactNode[] = []
  if (column.allowsSorting && onSort) {
    sorts.push(
      <MenuSection key="sorts" selectionMode="single" selectedKeys={sort?.column === id ? [sort.direction] : []}>
        <SortItem id="ascending" label={t('columns.sortAscending')} onAction={() => onSort({ column: id, direction: 'ascending' })} />
        <SortItem id="descending" label={t('columns.sortDescending')} onAction={() => onSort({ column: id, direction: 'descending' })} />
      </MenuSection>,
    )
  }
  if (relevance && sort) {
    sorts.push(
      <MenuItem key="relevance" id="relevance" onAction={relevance.onSelect}>
        {t('columns.sortByRelevance')}
      </MenuItem>,
    )
  }

  const fixed = column.isRowHeader === true
  const left = columns[index - 1]
  const groups: ReactNode[][] = [
    sorts,
    startResize
      ? [
          <MenuItem key="resize" id="resize" onAction={() => startResize(id)}>
            {t('columns.resize')}
          </MenuItem>,
        ]
      : [],
    choice && !fixed
      ? [
          left && !left.isRowHeader && (
            <MenuItem key="moveLeft" id="moveLeft" onAction={() => choice.onMove(id, index - 1)}>
              {t('columns.moveLeft')}
            </MenuItem>
          ),
          index < columns.length - 1 && (
            <MenuItem key="moveRight" id="moveRight" onAction={() => choice.onMove(id, index + 1)}>
              {t('columns.moveRight')}
            </MenuItem>
          ),
          <MenuItem key="hide" id="hide" onAction={() => choice.onHide(id)}>
            {t('columns.hide', { column: column.name })}
          </MenuItem>,
        ].filter(Boolean)
      : [],
    [
      choice && (
        <MenuItem key="choose" id="choose" onAction={options.onChoose}>
          {t('columns.choose')}
        </MenuItem>
      ),
      onResetColumns && (
        <MenuItem key="reset" id="reset" onAction={onResetColumns}>
          {t('columns.reset')}
        </MenuItem>
      ),
    ].filter(Boolean),
  ].filter((group) => group.length > 0)
  if (groups.length === 0) return undefined
  return {
    label: t('columns.menu', { column: column.name }),
    items: groups.flatMap((group, at) => (at === 0 ? group : [<MenuSeparator key={`separator${at}`} />, ...group])),
  }
}

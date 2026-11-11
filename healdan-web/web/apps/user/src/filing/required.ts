// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import type { components } from '@healdan/api'
import { isEditable } from '../details/FieldsForm'

type S = components['schemas']

/** Nothing in it: No (false) and 0 are values, as a required yes-or-no or number that defaults to them is filled */
export const isEmpty = (value: S['ProfileField']['value'] | undefined): boolean => value === null || value === undefined || value === ''

/** The editable required properties with no value after `values` (what the person changed; null clears one) is applied */
export function missingRequired(fields: S['ProfileField'][], values: Record<string, S['PropertyValue']> = {}): S['ProfileField'][] {
  return fields.filter((field) => isEditable(field) && field.required && isEmpty(field.name in values ? (values[field.name] as S['ProfileField']['value']) : field.value))
}

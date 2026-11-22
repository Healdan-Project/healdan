// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.logicaldoc.healdan.HealdanRuleException;

/**
 * A form's fields that an administrative change refuses, each with its code and sentence, which the admin routes
 * answer {@code 422 validation} with their {@code fields} (Admin app spec section 9.1). Its message is the fields'
 * sentences, so a route that doesn't know it answers {@code 409 refused} with them, as any rule's refusal. A refusal
 * changes nothing.
 */
public class FieldRefusal extends HealdanRuleException {

	private static final long serialVersionUID = 1L;

	/** A field that has to be given wasn't, or is blank */
	public static final String REQUIRED = "required";

	/** A field's value is longer than Healdan keeps */
	public static final String TOO_LONG = "too-long";

	/** A field's value isn't one Healdan takes */
	public static final String INVALID = "invalid";

	/** A field that the change can't make, such as a username, which never changes */
	public static final String NOT_EDITABLE = "not-editable";

	/** Another record already has the value, compared without regard to case */
	public static final String TAKEN = "taken";

	/**
	 * One field refused
	 *
	 * @param name the field, as the form names it, such as {@code username}
	 * @param code why: {@link #REQUIRED}, {@link #TOO_LONG}, {@link #INVALID}, {@link #TAKEN} or
	 *        {@link #NOT_EDITABLE}
	 * @param message an English sentence that says what's wrong, which the form shows as it is
	 */
	public record Field(String name, String code, String message) {

		public Field {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(code, "code");
			Objects.requireNonNull(message, "message");
		}
	}

	private final List<Field> fields;

	/**
	 * @param fields the fields refused, at least one, in the form's order
	 */
	public FieldRefusal(List<Field> fields) {
		super(fields.stream().map(Field::message).collect(Collectors.joining(" ")));
		if (fields.isEmpty())
			throw new IllegalArgumentException("A field refusal names a field");
		this.fields = List.copyOf(fields);
	}

	/**
	 * @return the fields refused, in the form's order
	 */
	public List<Field> fields() {
		return fields;
	}
}

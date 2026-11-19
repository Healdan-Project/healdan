// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import com.logicaldoc.healdan.admin.AdminArea;

/**
 * Marks a resource of the admin API, under {@code /v1/admin} (Admin app spec
 * section 9.1), with the area it belongs to. {@link AdminFilter} lets a
 * request to it through only on the web session of someone
 * {@link com.logicaldoc.healdan.admin.AdminAccess} allows the area. Every
 * resource under {@code /v1/admin} carries it, and only those do.
 */
@Target(TYPE)
@Retention(RUNTIME)
public @interface AdminRoute {

	/**
	 * @return the area the resource's routes belong to
	 */
	AdminArea value();
}

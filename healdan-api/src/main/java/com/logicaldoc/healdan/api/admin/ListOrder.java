// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.time.Instant;
import java.util.Comparator;
import java.util.Date;

import com.logicaldoc.healdan.api.ApiProblem;

/**
 * What the Admin app's sorted lists share, the Clients list's and the Matters list's: the {@code order} query
 * parameter, a column's order in it, and the figures' times as the contract gives them.
 */
final class ListOrder {

	private ListOrder() {
	}

	/**
	 * @param order the {@code order} query parameter
	 * @return whether it asks for the highest first
	 * @throws ApiProblem {@code 400 bad-request} for anything but {@code asc}, {@code desc} or nothing
	 */
	static boolean descending(String order) {
		if (order == null || order.equals("asc"))
			return false;
		if (order.equals("desc"))
			return true;
		throw ApiProblem.badRequest("Give the order as asc or desc.");
	}

	/**
	 * @param ascending a column's order, lowest first
	 * @param descending true for the highest first
	 * @return the order asked for
	 */
	static <T> Comparator<T> directed(Comparator<T> ascending, boolean descending) {
		return descending ? ascending.reversed() : ascending;
	}

	/**
	 * @param instant a moment, or null
	 * @return it as the lists' figures give it; null for none
	 */
	static Date date(Instant instant) {
		return instant != null ? Date.from(instant) : null;
	}
}

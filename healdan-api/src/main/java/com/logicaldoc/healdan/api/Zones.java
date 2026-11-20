// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.time.DateTimeException;
import java.time.ZoneId;

import org.apache.commons.lang.StringUtils;

/**
 * The {@code zone} a request names, the browser's IANA time zone, in which the server cuts its days (R90): the
 * listings' and searches' Today and date ranges, and the Admin app's Usage.
 */
public final class Zones {

	private Zones() {
	}

	/**
	 * @param zone a time zone's id as the request gives it, or null
	 * @return the zone, or null for none
	 * @throws ApiProblem {@code 400} for one {@link ZoneId#of} refuses
	 */
	public static ZoneId parse(String zone) {
		if (StringUtils.isEmpty(zone))
			return null;
		try {
			return ZoneId.of(zone);
		} catch (DateTimeException e) {
			throw ApiProblem.badRequest("The zone is a time zone's id, such as Europe/London, not %s.".formatted(zone));
		}
	}
}

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.Date;
import java.util.List;

/**
 * {@code openapi-admin.yaml}'s {@code Client}: a client with its live matters and their figures, from the summary the
 * server keeps in memory ({@code UsageSummary}).
 *
 * @param id the client's id
 * @param number its number, which never changes
 * @param name its name
 * @param matters how many live matters it has: not deleted, their folders not in the Trash
 * @param documents its live matters' documents, as the summary last counted them; null until it has been worked out
 * @param lastActivity when one of its live matters' documents was last filed or saved, as the summary last found it;
 *        null with none, or until it has been worked out
 */
public record ClientView(long id, long number, String name, long matters, Long documents, Date lastActivity) {

	/**
	 * {@code openapi-admin.yaml}'s {@code ClientList}: a page of clients, and when their figures were worked out.
	 *
	 * @param items the clients on this page
	 * @param offset how many come before them
	 * @param limit the most a page holds
	 * @param total how many clients there are, once {@code q} has filtered them
	 * @param figuresAt when the summary that gives {@code documents} and {@code lastActivity} was worked out; null
	 *        until it has been
	 */
	public record ClientList(List<ClientView> items, int offset, int limit, Long total, Date figuresAt) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code NextNumber}.
	 *
	 * @param number the number to suggest for a new client
	 */
	public record NextNumber(long number) {
	}
}

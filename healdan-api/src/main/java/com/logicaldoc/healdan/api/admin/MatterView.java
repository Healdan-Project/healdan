// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.admin;

import java.util.Date;
import java.util.List;

/**
 * {@code openapi-admin.yaml}'s {@code Matter}: a matter, for its page, with its figures from the summary the server
 * keeps in memory ({@code UsageSummary}) and its documents that couldn't be indexed or previewed, counted at the
 * request.
 *
 * @param id the matter's id, not its folder's
 * @param number its number with its client's, such as {@code 1001.1}
 * @param name its own name, without its number
 * @param description its description; null for none
 * @param client its client
 * @param store the Store its folder is in
 * @param owner who owns it; null when that person's account has been deleted
 * @param template the workspace template it was made from; null when it was made from none, or that template isn't
 *        live any more
 * @param documents its live documents, as the summary last counted them; null until it has been worked out
 * @param bytes the size of their files; null as {@code documents} is
 * @param lastActivity when one of them was last filed or saved; null with none, or until the summary has been worked
 *        out
 * @param figuresAt when the summary that gives the figures was worked out; null until it has been
 * @param folderId its own folder, the top of its workspace
 * @param indexFailed how many of its live documents couldn't be indexed
 * @param previewsGivenUp how many of its live documents' previews were given up on
 */
public record MatterView(long id, String number, String name, String description, ClientBrief client,
		StoreBrief store, PersonBrief owner, TemplateBrief template, Long documents, Long bytes, Date lastActivity,
		Date figuresAt, long folderId, long indexFailed, long previewsGivenUp) {

	/**
	 * {@code openapi-admin.yaml}'s {@code MatterRow}: a matter as the Matters list shows it, with the members of
	 * {@link MatterView} of the same names.
	 */
	public record MatterRow(long id, String number, String name, ClientBrief client, StoreBrief store,
			PersonBrief owner, TemplateBrief template, Long documents, Long bytes, Date lastActivity, long folderId) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code MatterList}: a page of matters, and when their figures were worked out.
	 *
	 * @param items the matters on this page
	 * @param offset how many come before them
	 * @param limit the most a page holds
	 * @param total how many matters there are, once the filters have applied
	 * @param figuresAt when the summary that gives the figures was worked out; null until it has been
	 */
	public record MatterList(List<MatterRow> items, int offset, int limit, Long total, Date figuresAt) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code ClientBrief}: a client named in another object.
	 */
	public record ClientBrief(long id, long number, String name) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code StoreBrief}: a Store named in another object.
	 */
	public record StoreBrief(long id, String name) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code TemplateBrief}: a workspace template named in another object, by its
	 * folder's id.
	 */
	public record TemplateBrief(long id, String name) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code PersonBrief}: a person named in another object.
	 *
	 * @param id the user's id
	 * @param username their username
	 * @param name their first and last name; the username when both are blank
	 */
	public record PersonBrief(long id, String username, String name) {
	}

	/**
	 * {@code openapi-admin.yaml}'s {@code DeletedMatter}: the Trash item a matter's delete made.
	 *
	 * @param trashItemId the item in the caller's Trash that holds the matter's folder
	 * @param folders the folders in the matter's folder that went with it, at every level, its own left out
	 * @param documents the documents that went with it
	 */
	public record DeletedMatter(long trashItemId, long folders, long documents) {
	}
}

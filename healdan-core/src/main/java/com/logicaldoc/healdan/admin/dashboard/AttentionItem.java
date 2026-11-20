// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.dashboard;

import java.util.List;

/**
 * One row of the Dashboard's Needs Attention (Admin app spec section 8.1): something an administrator should act on,
 * {@code openapi-admin.yaml}'s {@code AttentionItem}.
 *
 * @param code what needs attention, one of {@link #CODES}
 * @param count how many files, backends, documents, errors, previews, jobs or disks: at least 1
 * @param detail a line in English that says more, such as which Store
 * @param link where it's fixed: the Admin app's screen once it has shipped, the Classic Interface until then
 */
public record AttentionItem(String code, int count, String detail, String link) {

	/** Files the storage mover gave up moving to their Store's backend */
	public static final String FILES_STUCK = "files-stuck";

	/** A storage backend Healdan can't use: it can't be reached, or it's failing */
	public static final String BACKEND_UNREACHABLE = "backend-unreachable";

	/** Documents that couldn't be indexed */
	public static final String INDEX_FAILED = "index-failed";

	/** Errors logged in the last 24 hours that nobody has resolved */
	public static final String ERRORS = "errors";

	/** Previews given up on after their last try */
	public static final String PREVIEWS_GIVEN_UP = "previews-given-up";

	/** A background job whose last run failed */
	public static final String JOB_FAILED = "job-failed";

	/** A background job that hasn't run when it should have */
	public static final String JOB_OVERDUE = "job-overdue";

	/** A disk the server writes to that is more than {@value DashboardService#DISK_LOW_PERCENT}% full */
	public static final String DISK_LOW = "disk-low";

	/** Every code, in the order the Dashboard lists them, as the contract's {@code AttentionCode} does */
	public static final List<String> CODES = List.of(FILES_STUCK, BACKEND_UNREACHABLE, INDEX_FAILED, ERRORS,
			PREVIEWS_GIVEN_UP, JOB_FAILED, JOB_OVERDUE, DISK_LOW);
}

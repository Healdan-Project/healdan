// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api.model;

import java.util.List;

/**
 * The suggestions for the search box (M6 spec section 6.3)
 *
 * @param matters the matters the text names, best first
 * @param documents the documents whose title it starts or contains
 */
public record SuggestView(List<SuggestedMatter> matters, List<SuggestedDocument> documents) {

	/**
	 * @param id the matter's id
	 * @param number the matter number within its client
	 * @param name the matter's name
	 * @param client its client
	 */
	public record SuggestedMatter(long id, long number, String name, SuggestedClient client) {
	}

	/**
	 * @param number the client's number
	 * @param name the client's name
	 */
	public record SuggestedClient(long number, String name) {
	}

	/**
	 * @param docNumber the document's doc #
	 * @param name the file name
	 * @param matter the matter it is filed in
	 */
	public record SuggestedDocument(long docNumber, String name, SuggestedDocumentMatter matter) {
	}

	/**
	 * @param id the matter's id
	 * @param number the matter number within its client
	 * @param display the matter as people read it, such as
	 *        {@code 998.1 - Healdan Demo}
	 */
	public record SuggestedDocumentMatter(long id, long number, String display) {
	}
}

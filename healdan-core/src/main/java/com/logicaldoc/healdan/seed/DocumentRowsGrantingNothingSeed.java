// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.util.spring.Context;

/**
 * Deletes every row of a document's own ACL that grants nothing (spec
 * 2026-11-13-document-rows-that-grant-nothing), which a save of a document's
 * permissions now refuses ({@code PermissionsDocumentListener}). CE's
 * {@code getAllowedPermissions} reads such a row as no row, so a document
 * opens for the same people afterwards; CE's folder list and searches, which
 * hid it from the row's group, then show it to them too.
 */
@Component
public class DocumentRowsGrantingNothingSeed implements SeedStep {

	private static final Logger log = LoggerFactory.getLogger(DocumentRowsGrantingNothingSeed.class);

	@Override
	public String name() {
		return "document-rows-granting-nothing";
	}

	@Override
	public void run() throws Exception {
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		int count = jdbc.update("delete from ld_document_acl where (%s) = 0".formatted(AccessChecks.anyPermission("")));
		log.info("Document permission rows that granted nothing, removed: {}", count);
	}
}

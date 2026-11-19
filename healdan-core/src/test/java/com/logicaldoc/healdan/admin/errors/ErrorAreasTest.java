// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

/**
 * The Errors screen's areas, from the package of the logger that logged
 * (Admin app spec sections 8.8 and 9.4).
 */
public class ErrorAreasTest {

	@Test
	public void testTheAreas() {
		assertEquals(List.of("Previews", "Storage", "Search Index", "Email", "Sign-In", "Background Jobs", "Server"),
				ErrorAreas.ALL);
	}

	@Test
	public void testHealdansPackages() {
		assertEquals(ErrorAreas.PREVIEWS, ErrorAreas.of("com.logicaldoc.healdan.preview.PreviewWorker"));
		assertEquals(ErrorAreas.STORAGE, ErrorAreas.of("com.logicaldoc.healdan.storage.mover.Mover"));
		assertEquals(ErrorAreas.SEARCH_INDEX, ErrorAreas.of("com.logicaldoc.healdan.parser.MessageParser"));
		assertEquals(ErrorAreas.EMAIL, ErrorAreas.of("com.logicaldoc.healdan.email.EmailIndexWorker"));
		assertEquals(ErrorAreas.EMAIL, ErrorAreas.of("com.logicaldoc.healdan.api.EmailFiling"));
		assertEquals(ErrorAreas.SIGN_IN, ErrorAreas.of("com.logicaldoc.healdan.signin.ProviderClient"));
		assertEquals(ErrorAreas.SIGN_IN, ErrorAreas.of("com.logicaldoc.healdan.token.ApiTokens"));
		assertEquals(ErrorAreas.SIGN_IN, ErrorAreas.of("com.logicaldoc.healdan.api.SignInResource"));
		assertEquals(ErrorAreas.BACKGROUND_JOBS, ErrorAreas.of("com.logicaldoc.healdan.trash.TrashDestroyer"));
		assertEquals(ErrorAreas.SERVER, ErrorAreas.of("com.logicaldoc.healdan.trash.TrashRecorder"));
		assertEquals(ErrorAreas.SERVER, ErrorAreas.of("com.logicaldoc.healdan.api.DocumentResource"));
	}

	@Test
	public void testLogicalDocsPackages() {
		assertEquals(ErrorAreas.PREVIEWS, ErrorAreas.of("com.logicaldoc.core.conversion.FormatConverterManager"));
		assertEquals(ErrorAreas.PREVIEWS, ErrorAreas.of("com.logicaldoc.core.document.thumbnail.ThumbnailManager"));
		assertEquals(ErrorAreas.STORAGE, ErrorAreas.of("com.logicaldoc.core.store.FSStore"));
		assertEquals(ErrorAreas.STORAGE, ErrorAreas.of("STORE_DELETIONS"));
		assertEquals(ErrorAreas.SEARCH_INDEX, ErrorAreas.of("com.logicaldoc.core.searchengine.indexer.IndexerTask"));
		assertEquals(ErrorAreas.SEARCH_INDEX, ErrorAreas.of("com.logicaldoc.core.parser.PDFParser"));
		assertEquals(ErrorAreas.SEARCH_INDEX, ErrorAreas.of("org.apache.solr.core.SolrCore"));
		assertEquals(ErrorAreas.EMAIL, ErrorAreas.of("com.logicaldoc.core.communication.EMailSender"));
		assertEquals(ErrorAreas.SIGN_IN,
				ErrorAreas.of("com.logicaldoc.core.security.authentication.DefaultAuthenticator"));
		assertEquals(ErrorAreas.SIGN_IN, ErrorAreas.of("com.logicaldoc.core.security.SessionManager"));
		assertEquals(ErrorAreas.BACKGROUND_JOBS, ErrorAreas.of("com.logicaldoc.core.task.Task"));
		assertEquals(ErrorAreas.BACKGROUND_JOBS, ErrorAreas.of("com.logicaldoc.core.stats.StatsCollector"));
		assertEquals(ErrorAreas.BACKGROUND_JOBS, ErrorAreas.of("com.logicaldoc.core.document.DigestProcessor"));
		assertEquals(ErrorAreas.BACKGROUND_JOBS, ErrorAreas.of("com.logicaldoc.core.document.TagsProcessor$1"));
		assertEquals(ErrorAreas.BACKGROUND_JOBS, ErrorAreas.of("com.logicaldoc.core.folder.PathCalculator"));
		assertEquals(ErrorAreas.BACKGROUND_JOBS, ErrorAreas.of("org.quartz.core.JobRunShell"));
		assertEquals(ErrorAreas.SERVER, ErrorAreas.of("com.logicaldoc.core.document.DocumentManager"));
		assertEquals(ErrorAreas.SERVER, ErrorAreas.of("org.hibernate.engine.jdbc.spi.SqlExceptionHelper"));
	}

	@Test
	public void testOnlyWholeNamesMatch() {
		assertEquals(ErrorAreas.SERVER, ErrorAreas.of("com.logicaldoc.healdan.previewer.Thing"));
		assertEquals(ErrorAreas.SERVER, ErrorAreas.of("com.logicaldoc.core.document.DigestProcessorHelper"));
		assertEquals(ErrorAreas.PREVIEWS, ErrorAreas.of("com.logicaldoc.healdan.preview"));
		assertEquals(ErrorAreas.SERVER, ErrorAreas.of(""));
		assertEquals(ErrorAreas.SERVER, ErrorAreas.of(null));
	}
}

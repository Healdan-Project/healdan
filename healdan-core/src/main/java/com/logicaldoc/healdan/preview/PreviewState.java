// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.preview;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

import org.apache.commons.io.FilenameUtils;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.store.Store;
import com.logicaldoc.core.store.StoreResource;

/**
 * Where a file version's preview stands (M6 spec section 9.3): there is a PDF to show, it's being made, or there never
 * will be one. It never converts anything: the worker does ({@link PreviewWorker}), so asking is cheap and a client
 * can ask again.
 */
public enum PreviewState {

	/** A PDF to show: the file itself if it is one, else its stored conversion */
	READY,

	/** Queued for conversion and not given up on: ask again soon */
	PENDING,

	/** No preview: a type that has none, a conversion that failed (the stored "not available" PDF), or a job given up on */
	NONE;

	/**
	 * @param document the document
	 * @param fileVersion the file version, which may be an older one than the document's current
	 * @return the state of the preview of the file version, named as the document's own file is
	 * @throws UncheckedIOException if storage can't be read; a storage outage is its cause, as {@code StorageUnreachableException}
	 */
	public static PreviewState of(Document document, String fileVersion) {
		return of(document, fileVersion, document.getFileName());
	}

	/**
	 * @param document the document
	 * @param fileVersion the file version
	 * @param fileName the name of that file version's file, whose extension tells a PDF
	 * @return the state of the preview of the file version
	 * @throws UncheckedIOException if storage can't be read
	 */
	public static PreviewState of(Document document, String fileVersion, String fileName) {
		if ("pdf".equalsIgnoreCase(FilenameUtils.getExtension(fileName)))
			return READY;
		try {
			StoreResource conversion = conversion(document.getId(), fileVersion);
			Store store = Store.get();
			if (store.exists(conversion))
				return PreviewWorker.isPlaceholder(store, conversion) ? NONE : READY;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		// A job that failed every try is kept as a record, and no one is converting it
		Optional<PreviewJob> job = PreviewJobs.get().find(document.getId(), fileVersion);
		return job.isPresent() && job.get().attempts() < PreviewJobs.MAX_ATTEMPTS ? PENDING : NONE;
	}

	/**
	 * @param docId the document
	 * @param fileVersion the file version
	 * @return where the file version's PDF conversion is stored
	 */
	public static StoreResource conversion(long docId, String fileVersion) {
		return StoreResource.builder().docId(docId).fileVersion(fileVersion).suffixPdfConversion().build();
	}
}

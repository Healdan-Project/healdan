// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import java.io.IOException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.IndexingStatus;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.AdminRecord;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.util.CacheEvictor;
import com.logicaldoc.util.spring.Context;

/**
 * The layout the reindex tests share: Store Client Work with matters 1001.1 Charter Dispute (Documents and
 * Pleadings) and 1001.2 Lease Review (Documents), and Store Firm with matter 1002.1 Tribunal (Documents), an empty
 * index, and the reindex seams put back after each test.
 */
public abstract class AbstractReindexTestCase extends AbstractHealdanTestCase {

	protected final HealdanFixtures fx = new HealdanFixtures();

	protected JdbcTemplate jdbc;

	protected User admin;

	protected Folder clientWork;

	protected Folder firm;

	protected Matter charter;

	protected Matter lease;

	protected Matter tribunal;

	protected Folder charterDocuments;

	protected Folder pleadings;

	protected Folder leaseDocuments;

	protected Folder tribunalDocuments;

	protected ReindexService service;

	protected ReindexWorker worker;

	/** How many rows the administration record had once the layout was made */
	private int recordedBefore;

	@Before
	public void prepareLayout() throws Exception {
		SearchEngine.get().unlock();
		SearchEngine.get().dropIndex();
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		admin = fx.admin();
		clientWork = fx.store("Client Work");
		firm = fx.store("Firm");
		var ashcombe = fx.client(1001, "Ashcombe");
		charter = fx.matter(ashcombe, 1, "Charter Dispute", clientWork, "Documents", "Pleadings");
		lease = fx.matter(ashcombe, 2, "Lease Review", clientWork, "Documents");
		tribunal = fx.matter(fx.client(1002, "Brightwell"), 1, "Tribunal", firm, "Documents");
		charterDocuments = top(charter, "Documents");
		pleadings = top(charter, "Pleadings");
		leaseDocuments = top(lease, "Documents");
		tribunalDocuments = top(tribunal, "Documents");
		service = ReindexService.get();
		worker = Context.get(ReindexWorker.class);
		recordedBefore = AdminRecord.events().size();
	}

	/** @return the administration record's rows since the layout was made, which records its matters */
	protected List<String> records() {
		return AdminRecord.eventsSince(recordedBefore);
	}

	/** @return the doc # of the document of that file name, deleted or not */
	protected long numberOf(String fileName) {
		return Long.parseLong(jdbc.queryForObject("select ld_customid from ld_document where ld_filename = ?",
				String.class, fileName));
	}

	@After
	public void putBackReindex() throws IOException {
		ReindexTestHooks.reset();
		try {
			HealdanFixtures.awaitVersionSaves();
		} catch (Exception e) {
			// The database goes anyway
		}
		SearchEngine.get().unlock();
		SearchEngine.get().close();
	}

	protected static Folder top(Matter matter, String name) throws Exception {
		return FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), name, Tenant.DEFAULT_ID, true)
				.get(0);
	}

	/**
	 * @return a text document filed in the folder, with the words given, its version saves done
	 */
	protected Document document(Folder folder, String name, String text) throws Exception {
		Document document = fx.upload(folder, name, text, admin);
		HealdanFixtures.awaitVersionSaves();
		return DocumentDAO.get().findById(document.getId());
	}

	protected Document document(Folder folder, String name) throws Exception {
		return document(folder, name, "the words of " + name);
	}

	/** Sets a document's indexing status, as CE's own screens and the Indexer do, past the cache */
	protected void status(Document document, IndexingStatus status) {
		jdbc.update("update ld_document set ld_indexed = ? where ld_id = ?", status.ordinal(), document.getId());
		CacheEvictor.get().evictDocuments(List.of(document.getId()));
	}

	protected int indexed(Document document) {
		return jdbc.queryForObject("select ld_indexed from ld_document where ld_id = ?", Integer.class,
				document.getId());
	}

	/** Puts a document in a running CE transaction: a lock held under the transaction its row names */
	protected void inTransaction(Document document, String transactionId) {
		long id = jdbc.queryForObject("select coalesce(max(ld_id), 0) + 1000 from ld_generic", Long.class);
		jdbc.update("insert into ld_generic (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,"
				+ " ld_tenantid, ld_type, ld_subtype, ld_string1) values (?, now(), now(), 1, 0, 1, 'lock', ?, ?)", id,
				"test-" + id, transactionId);
		jdbc.update("update ld_document set ld_transactionid = ? where ld_id = ?", transactionId, document.getId());
		CacheEvictor.get().evictDocuments(List.of(document.getId()));
	}

	/** @return the document's number, as the doc # the API names it by */
	protected static long number(Document document) {
		return Long.parseLong(document.getCustomId());
	}
}

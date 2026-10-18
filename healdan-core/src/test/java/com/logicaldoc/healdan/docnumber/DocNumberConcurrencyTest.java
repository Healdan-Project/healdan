package com.logicaldoc.healdan.docnumber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * Concurrent creates never get the same doc #.
 */
public class DocNumberConcurrencyTest extends AbstractHealdanTestCase {

	private static final int THREADS = 8;

	private static final int PER_THREAD = 25;

	@Test
	public void testNoDuplicates() throws Exception {
		HealdanFixtures fx = new HealdanFixtures();
		User admin = fx.admin();
		Folder store = fx.store("Main");
		Folder workspace = FolderDAO.get().findById(
				fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		Folder documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);

		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		List<Future<Void>> futures = new ArrayList<>();
		for (int t = 0; t < THREADS; t++) {
			final int thread = t;
			Callable<Void> work = () -> {
				for (int i = 0; i < PER_THREAD; i++) {
					Document doc = fx.upload(documents, "t" + thread + "-" + i + ".txt", "text " + thread + i, admin);
					assertTrue(doc.getId() > 0);
				}
				return null;
			};
			futures.add(pool.submit(work));
		}
		for (Future<Void> f : futures)
			f.get(5, TimeUnit.MINUTES);
		pool.shutdown();

		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		List<String> ids = jdbc.queryForList("select ld_customid from ld_document where ld_deleted = 0", String.class);
		Set<String> distinct = new HashSet<>(ids);
		assertEquals(THREADS * PER_THREAD, ids.size());
		assertEquals(THREADS * PER_THREAD, distinct.size());
		for (int n = 1; n <= THREADS * PER_THREAD; n++)
			assertTrue("missing " + n, distinct.contains(Integer.toString(n)));
	}
}

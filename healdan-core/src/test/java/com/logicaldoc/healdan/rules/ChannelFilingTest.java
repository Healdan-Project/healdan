package com.logicaldoc.healdan.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.chemistry.opencmis.commons.exceptions.CmisStorageException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.cmis.LDRepository;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.channels.ChannelHarness;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;

/**
 * The filing rule holds on CE's external channels too: an upload to a matter
 * workspace's top level is refused with nothing written, and an upload into
 * one of its folders lands there. So does the top-level rule on CMIS's
 * deleteTree, which deletes a tree piece by piece.
 */
public class ChannelFilingTest extends AbstractRulesTestCase {

	private final ChannelHarness harness = new ChannelHarness();

	private Session session;

	@Override
	protected List<String> getDatabaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "sql/logicaldoc-webservice.sql", "data.sql");
	}

	@Before
	public void openSession() throws Exception {
		session = SessionManager.get().newSession("admin", "admin", (Client) null);
	}

	@After
	public void closeSession() {
		if (session != null)
			SessionManager.get().kill(session.getSid());
	}

	@Test
	public void testRest() throws Exception {
		assertRefused(DOCUMENT_TOP_LEVEL, () -> harness.restCreate(workspace.getId(), "rest.txt", bytes("rest")));

		allowed(() -> harness.restCreate(documents.getId(), "rest.txt", bytes("rest")));
		assertInDocuments("rest.txt");
	}

	@Test
	public void testWebdav() throws Exception {
		String workspacePath = "/" + store.getName() + "/" + workspace.getName();
		assertRefused(DOCUMENT_TOP_LEVEL, () -> harness.webdavPut(workspacePath + "/dav.txt", bytes("dav"), admin));

		allowed(() -> harness.webdavPut(workspacePath + "/Documents/dav.txt", bytes("dav"), admin));
		assertInDocuments("dav.txt");
	}

	@Test
	public void testCmis() throws Exception {
		assertRefused(DOCUMENT_TOP_LEVEL,
				() -> harness.cmisCreateDocument(workspace.getId(), "cmis.txt", bytes("cmis"), session));

		allowed(() -> harness.cmisCreateDocument(documents.getId(), "cmis.txt", bytes("cmis"), session));
		assertInDocuments("cmis.txt");
	}

	@Test
	public void testCmisDeleteTree() throws Exception {
		/*
		 * CMIS deletes each document and subfolder in its own commit before the
		 * folder itself, so the rule must refuse the tree first. The refusal
		 * reaches the CMIS client as a storage error, and the log says why.
		 */
		Session aliceSession = SessionManager.get().newSession(alice.getUsername(), HealdanFixtures.PASSWORD,
				(Client) null);
		try (LogCapture log = LogCapture.of(LDRepository.class)) {
			assertRefused(TOP_LEVEL, e -> {
				assertTrue(e.toString(), e instanceof CmisStorageException);
				assertTrue(log.lines().toString(), log.lines().stream().anyMatch(line -> line.contains(TOP_LEVEL)));
			}, () -> harness.cmisFor(correspondence.getId(), aliceSession).deleteTree(null,
					"fld." + correspondence.getId(), false));
		} finally {
			SessionManager.get().kill(aliceSession.getSid());
		}

		allowed(() -> harness.cmisFor(correspondence.getId(), session).deleteTree(null, "fld." + correspondence.getId(),
				false));
		assertNull(folderDao.findById(correspondence.getId()));
		assertNull(documentDao.findById(doc.getId()));
	}

	private void assertInDocuments(String fileName) throws Exception {
		assertEquals(1, documentDao.findByFileNameAndParentFolderId(documents.getId(), fileName, null,
				Tenant.DEFAULT_ID, null).size());
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}

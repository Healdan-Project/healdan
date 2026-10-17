package com.logicaldoc.healdan.rules;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.healdan.channels.ChannelHarness;

/**
 * The filing rule holds on CE's external channels too: an upload to a matter
 * workspace's top level is refused with nothing written, and an upload into
 * one of its folders lands there.
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

	private void assertInDocuments(String fileName) throws Exception {
		assertEquals(1, documentDao.findByFileNameAndParentFolderId(documents.getId(), fileName, null,
				Tenant.DEFAULT_ID, null).size());
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}

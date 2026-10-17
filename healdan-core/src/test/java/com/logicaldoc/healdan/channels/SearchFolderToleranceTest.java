package com.logicaldoc.healdan.channels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.cmis.LDRepository;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.SearchEngine;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.DaoSnapshot;
import com.logicaldoc.healdan.support.DbSnapshot;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.IndexSnapshot;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;
import com.logicaldoc.webdav.resource.model.Resource;
import com.logicaldoc.webservice.model.WSFolder;

/**
 * CE's external channels list a folder of type 1001 (a search folder) like any
 * other folder. Without Healdan's rules they would also accept an upload into
 * it (plan, D4); the filing rule refuses that upload on every channel, and
 * nothing is written.
 */
public class SearchFolderToleranceTest extends AbstractHealdanTestCase {

	private static final String FOLDER_NAME = "search1001";

	private final ChannelHarness harness = new ChannelHarness();

	private final HealdanFixtures fx = new HealdanFixtures();

	/** The Store the matter is in: the CE workspace the CMIS repository serves */
	private Folder workspace;

	/** The matter's Documents folder, which holds the search folder */
	private Folder documents;

	private Folder searchFolder;

	/** The WebDAV path of {@link #documents} */
	private String documentsPath;

	private User admin;

	private Session session;

	@Override
	protected List<String> getDatabaseScripts() {
		return List.of("sql/logicaldoc-core.sql", "sql/logicaldoc-webservice.sql", "data.sql");
	}

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		try {
			workspace = fx.store("Clients");
			Matter matter = fx.matter(fx.client(998, "Healdan"), 1, "Healdan Demo", workspace, "Documents");
			Folder matterWorkspace = FolderDAO.get().findById(matter.getFolderId());
			documents = FolderDAO.get().findByName(matterWorkspace, "Documents", workspace.getTenantId(), true).get(0);
			documentsPath = "/%s/%s/Documents".formatted(workspace.getName(), matterWorkspace.getName());

			searchFolder = fx.searchFolder(documents, FOLDER_NAME);
			assertEquals(HealdanConstants.FOLDER_TYPE_SEARCH, FolderDAO.get().findById(searchFolder.getId()).getType());

			admin = UserDAO.get().findByUsername("admin");
			session = SessionManager.get().newSession("admin", "admin", (Client) null);
		} catch (Exception e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	@Override
	public void tearDown() throws IOException {
		SessionManager.get().kill(session.getSid());
		SearchEngine.get().unlock();
		SearchEngine.get().close();
		super.tearDown();
	}

	@Test
	public void testWebdavListsSearchFolderAsCollection() throws Exception {
		Resource parent = harness.webdav().getResource(documentsPath, harness.davSession(admin));
		assertNotNull(parent);

		Resource listed = harness.webdav().getChildResources(parent).stream()
				.filter(r -> FOLDER_NAME.equals(r.getName())).findFirst().orElse(null);
		assertNotNull(listed);
		assertTrue(listed.isFolder());
	}

	@Test
	public void testCmisListsSearchFolderAsFolder() throws Exception {
		LDRepository repository = harness.cmisFor(documents.getId(), session);

		ObjectInFolderData listed = repository
				.getChildren(callContext(), "fld." + documents.getId(), null, false, false, 100, 0, null).getObjects()
				.stream().filter(o -> ("fld." + searchFolder.getId()).equals(o.getObject().getId())).findFirst()
				.orElse(null);
		assertNotNull(listed);
		assertEquals(BaseTypeId.CMIS_FOLDER, listed.getObject().getBaseTypeId());
	}

	@Test
	public void testSoapListsSearchFolder() throws Exception {
		List<WSFolder> children = harness.soapFolders().listChildren("", documents.getId());
		assertTrue(children.stream().anyMatch(f -> f.getId() == searchFolder.getId()));
	}

	@Test
	public void testRestUploadIntoSearchFolderIsRefused() throws Exception {
		assertUploadRefused(() -> harness.restCreate(searchFolder.getId(), "rest.txt", "rest".getBytes()));
	}

	@Test
	public void testWebdavUploadIntoSearchFolderIsRefused() throws Exception {
		assertUploadRefused(
				() -> harness.webdavPut(documentsPath + "/" + FOLDER_NAME + "/dav.txt", "dav".getBytes(), admin));
	}

	@Test
	public void testCmisUploadIntoSearchFolderIsRefused() throws Exception {
		assertUploadRefused(
				() -> harness.cmisCreateDocument(searchFolder.getId(), "cmis.txt", "cmis".getBytes(), session));
	}

	@Test
	public void testCmisUploadIntoAnotherWorkspace() throws Exception {
		Folder other = fx.store("Other workspace");
		Matter otherMatter = fx.matter(fx.client(999, "Other"), 1, "Elsewhere", other, "Correspondence");
		Folder target = FolderDAO.get().findByName(FolderDAO.get().findById(otherMatter.getFolderId()),
				"Correspondence", workspace.getTenantId(), true).get(0);

		// The CMIS servlet serves each CE workspace, here a Store, as its own repository
		assertEquals(other.getId(), harness.cmisFor(target.getId(), session).getRoot().getId());
		assertEquals(workspace.getId(), harness.cmisFor(searchFolder.getId(), session).getRoot().getId());

		harness.cmisCreateDocument(target.getId(), "elsewhere.txt", "cmis".getBytes(), session);

		assertEquals(1, DocumentDAO.get().findByFileNameAndParentFolderId(target.getId(), "elsewhere.txt", null,
				workspace.getTenantId(), null).size());
	}

	/**
	 * Runs an upload into the search folder and proves that the filing rule
	 * refused it and nothing was written
	 */
	private void assertUploadRefused(Upload upload) throws Exception {
		DbSnapshot db = fx.snapshot();
		IndexSnapshot index = IndexSnapshot.of();
		DaoSnapshot dao = DaoSnapshot.take(new JdbcTemplate(Context.get(DataSource.class)));
		try {
			upload.run();
			fail("The upload into the search folder wasn't refused");
		} catch (Exception e) {
			assertTrue(e.toString(), refusedBy(e, "Search folders can't hold documents."));
		}
		db.assertUnchanged();
		index.assertUnchanged();
		dao.assertUnchanged();
		assertTrue(DocumentDAO.get().findByFolder(searchFolder.getId(), null).isEmpty());
	}

	private static boolean refusedBy(Throwable e, String message) {
		for (Throwable t = e; t != null; t = t.getCause())
			if (t instanceof HealdanRuleException && message.equals(t.getMessage()))
				return true;
		return false;
	}

	@FunctionalInterface
	private interface Upload {
		void run() throws Exception;
	}

	/** A CMIS call context that names the admin and asks for no object info. */
	private CallContext callContext() {
		return (CallContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { CallContext.class },
				(proxy, method, args) -> switch (method.getName()) {
					case "getUsername" -> "admin";
					case "isObjectInfoRequired" -> false;
					case "getRepositoryId" -> Long.toString(workspace.getId());
					default -> null;
				});
	}
}

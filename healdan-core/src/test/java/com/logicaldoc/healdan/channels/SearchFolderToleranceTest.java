package com.logicaldoc.healdan.channels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;

import org.apache.chemistry.opencmis.commons.data.ObjectInFolderData;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.server.CallContext;
import org.junit.Test;

import com.logicaldoc.cmis.LDRepository;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.webdav.resource.model.Resource;
import com.logicaldoc.webservice.model.WSFolder;

/**
 * Before any Healdan rule exists, CE's external channels must list a folder of
 * type 1001 (a search folder) like any other folder, and accept an upload into
 * it. Only Healdan's rules will block that upload later.
 */
public class SearchFolderToleranceTest extends AbstractHealdanTestCase {

	private static final String FOLDER_NAME = "search1001";

	private final ChannelHarness harness = new ChannelHarness();

	private Folder workspace;

	private Folder searchFolder;

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
			workspace = FolderDAO.get().findFolder(Folder.DEFAULTWORKSPACEID);
			assertNotNull(workspace);

			Folder search = new Folder(FOLDER_NAME);
			search.setType(HealdanConstants.FOLDER_TYPE_SEARCH);
			search.setTenantId(workspace.getTenantId());
			searchFolder = FolderDAO.get().create(workspace, search, true, new FolderHistory());
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
		super.tearDown();
	}

	@Test
	public void testWebdavListsSearchFolderAsCollection() throws Exception {
		Resource parent = harness.webdav().getResource("/Default", harness.davSession(admin));
		assertNotNull(parent);

		Resource listed = harness.webdav().getChildResources(parent).stream()
				.filter(r -> FOLDER_NAME.equals(r.getName())).findFirst().orElse(null);
		assertNotNull(listed);
		assertTrue(listed.isFolder());
	}

	@Test
	public void testCmisListsSearchFolderAsFolder() throws Exception {
		LDRepository repository = harness.cmis(workspace, session);

		ObjectInFolderData listed = repository
				.getChildren(callContext(), "fld." + workspace.getId(), null, false, false, 100, 0, null).getObjects()
				.stream().filter(o -> ("fld." + searchFolder.getId()).equals(o.getObject().getId())).findFirst()
				.orElse(null);
		assertNotNull(listed);
		assertEquals(BaseTypeId.CMIS_FOLDER, listed.getObject().getBaseTypeId());
	}

	@Test
	public void testSoapListsSearchFolder() throws Exception {
		List<WSFolder> children = harness.soapFolders().listChildren("", workspace.getId());
		assertTrue(children.stream().anyMatch(f -> f.getId() == searchFolder.getId()));
	}

	@Test
	public void testRestUploadIntoSearchFolderSucceeds() throws Exception {
		long id = harness.restCreate(searchFolder.getId(), "rest.txt", "rest".getBytes());
		assertInSearchFolder(id);
	}

	@Test
	public void testWebdavUploadIntoSearchFolderSucceeds() throws Exception {
		harness.webdavPut("/Default/" + FOLDER_NAME + "/dav.txt", "dav".getBytes(), admin);
		assertEquals(1, DocumentDAO.get().findByFileNameAndParentFolderId(searchFolder.getId(), "dav.txt", null,
				workspace.getTenantId(), null).size());
	}

	@Test
	public void testCmisUploadIntoSearchFolderSucceeds() throws Exception {
		harness.cmisCreateDocument(searchFolder.getId(), "cmis.txt", "cmis".getBytes(), session);
		assertEquals(1, DocumentDAO.get().findByFileNameAndParentFolderId(searchFolder.getId(), "cmis.txt", null,
				workspace.getTenantId(), null).size());
	}

	private void assertInSearchFolder(long docId) throws Exception {
		Document doc = DocumentDAO.get().findById(docId);
		assertNotNull(doc);
		assertEquals(searchFolder.getId(), doc.getFolder().getId());
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

package com.logicaldoc.healdan.channels;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;

import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertiesImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyIdImpl;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.PropertyStringImpl;
import org.apache.jackrabbit.webdav.DavException;

import com.logicaldoc.cmis.LDRepository;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.util.spring.Context;
import com.logicaldoc.webdav.context.ImportContextImpl;
import com.logicaldoc.webdav.resource.model.Resource;
import com.logicaldoc.webdav.resource.model.ResourceImpl;
import com.logicaldoc.webdav.resource.service.ResourceService;
import com.logicaldoc.webdav.resource.service.ResourceServiceImpl;
import com.logicaldoc.webdav.session.DavSessionImpl;
import com.logicaldoc.webdav.session.WebdavSession;
import com.logicaldoc.webservice.model.WSDocument;
import com.logicaldoc.webservice.soap.endpoint.SoapDocumentService;
import com.logicaldoc.webservice.soap.endpoint.SoapFolderService;

/**
 * Drives uploads and folder listings through CE's external channels (SOAP and
 * REST, WebDAV, CMIS), calling each channel's service classes in-process.
 */
public class ChannelHarness {

	/**
	 * @return a SOAP document service that does not validate the session id,
	 *         so any sid string works
	 */
	public SoapDocumentService soapDocuments() {
		SoapDocumentService service = new SoapDocumentService();
		service.setValidateSession(false);
		return service;
	}

	/**
	 * @return a SOAP folder service that does not validate the session id
	 */
	public SoapFolderService soapFolders() {
		SoapFolderService service = new SoapFolderService();
		service.setValidateSession(false);
		return service;
	}

	/**
	 * @return a SOAP folder service that validates the session id, so that each
	 *         call runs as the user of a session {@code SessionManager} opened
	 */
	public SoapFolderService soapFoldersWithSessions() {
		return new SoapFolderService();
	}

	/**
	 * @return the WebDAV resource service bean
	 */
	public ResourceService webdav() {
		return Context.get(ResourceServiceImpl.class);
	}

	/**
	 * @param u the user the WebDAV requests run as
	 * @return a WebDAV session for the user
	 */
	public WebdavSession davSession(User u) {
		DavSessionImpl session = new DavSessionImpl();
		session.putObject("id", u.getId());
		session.putObject("user", u);
		return session;
	}

	/**
	 * @param root the folder the repository is rooted at
	 * @param s the session the CMIS calls run in
	 * @return the CMIS repository
	 */
	public LDRepository cmis(Folder root, Session s) {
		return new LDRepository(root, s.getSid());
	}

	/**
	 * The CMIS servlet serves each workspace as its own repository, so a
	 * folder is reached through the repository of its workspace.
	 * 
	 * @param folderId a folder
	 * @param s the session the CMIS calls run in
	 * @return the CMIS repository rooted at the folder's workspace
	 */
	public LDRepository cmisFor(long folderId, Session s) throws PersistenceException {
		return cmis(FolderDAO.get().findWorkspace(folderId), s);
	}

	/**
	 * Uploads through the REST channel, which delegates to
	 * {@code SoapDocumentService.create}.
	 * 
	 * @return the id of the new document
	 */
	public long restCreate(long folderId, String name, byte[] content) throws Exception {
		WSDocument document = new WSDocument();
		document.setFolderId(folderId);
		document.setFileName(name);
		return soapDocuments().create("", document, new ByteArrayInputStream(content)).getId();
	}

	/**
	 * Uploads through WebDAV as a PUT of a new file would.
	 * 
	 * @param path the path of the new file, such as {@code /Default/Folder/a.pdf}
	 */
	public void webdavPut(String path, byte[] content, User u) throws DavException, IOException {
		WebdavSession session = davSession(u);
		int slash = path.lastIndexOf('/');
		Resource parent = webdav().getResource(path.substring(0, slash), session);
		String name = path.substring(slash + 1);
		Resource newResource = new ResourceImpl();
		newResource.setName(name);
		ImportContextImpl context = new ImportContextImpl(newResource, null, new ByteArrayInputStream(content));
		webdav().createResource(parent, name, false, context, session);
	}

	/**
	 * Uploads through CMIS as {@code createDocument} would, in the repository of
	 * the target folder's workspace.
	 */
	public void cmisCreateDocument(long folderId, String name, byte[] content, Session s) throws PersistenceException {
		PropertiesImpl props = new PropertiesImpl();
		PropertyIdImpl type = new PropertyIdImpl(PropertyIds.OBJECT_TYPE_ID, "cmis:document");
		type.setQueryName(PropertyIds.OBJECT_TYPE_ID);
		props.addProperty(type);
		PropertyStringImpl nameProperty = new PropertyStringImpl(PropertyIds.NAME, name);
		nameProperty.setQueryName(PropertyIds.NAME);
		props.addProperty(nameProperty);

		ContentStreamImpl stream = new ContentStreamImpl(name, BigInteger.valueOf(content.length),
				"application/octet-stream", new ByteArrayInputStream(content));
		cmisFor(folderId, s).createDocument(null, props, "fld." + folderId, stream);
	}
}

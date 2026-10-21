package com.logicaldoc.healdan.web;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIDocNumberResult;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanLookupService;
import com.logicaldoc.healdan.docnumber.DocNumberLookup;
import com.logicaldoc.healdan.docnumber.LookupResult;

import jakarta.servlet.annotation.WebServlet;

/**
 * The search box's doc # lookup (spec 6.5), for any user: what the user can't
 * read is reported as not there, by {@link DocNumberLookup}.
 */
@WebServlet("/frontend/healdanlookup")
public class HealdanLookupServiceImpl extends HealdanRemoteService implements HealdanLookupService {

	private static final long serialVersionUID = 1L;

	@Override
	public GUIDocNumberResult lookup(String input) throws ServerException {
		User user = session().getUser();
		return call("look up the document number", () -> toGUI(DocNumberLookup.lookup(input, user.getId())));
	}

	private static GUIDocNumberResult toGUI(LookupResult result) {
		GUIDocNumberResult gui = new GUIDocNumberResult();
		gui.setDocId(result.docId());
		gui.setFolderId(result.folderId());
		gui.setVersionId(result.versionId());
		gui.setVersion(result.version());
		gui.setFileVersion(result.fileVersion());
		gui.setMessage(result.message());
		return gui;
	}
}

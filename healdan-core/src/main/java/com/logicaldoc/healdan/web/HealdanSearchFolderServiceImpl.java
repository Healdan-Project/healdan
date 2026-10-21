package com.logicaldoc.healdan.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUISearchFolderRule;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanSearchFolderService;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.profile.ProfileSchema;
import com.logicaldoc.healdan.searchfolder.SearchFolderRule;
import com.logicaldoc.healdan.searchfolder.SearchFolderRuleDAO;
import com.logicaldoc.healdan.searchfolder.SearchFolderService;

import jakarta.servlet.annotation.WebServlet;

/**
 * The search folder dialog's and listing's server side (spec 5.4, 8.3).
 * {@link SearchFolderService} decides who may create or edit a search folder
 * where; its refusals reach the dialog as they are.
 */
@WebServlet("/frontend/healdansearchfolder")
public class HealdanSearchFolderServiceImpl extends HealdanRemoteService implements HealdanSearchFolderService {

	private static final long serialVersionUID = 1L;

	/**
	 * The most documents a listing asks for. The grid loads them in one
	 * request, and an id list much longer would pass Tomcat's header limit.
	 */
	static final int MAX_LISTED = 1000;

	@Override
	public GUISearchFolderRule get(long folderId) throws ServerException {
		User user = session().getUser();
		return call("read the search folder", () -> {
			Folder folder = FolderDAO.get().findById(folderId);
			// A folder the user can't read says nothing about itself, not even that it exists
			if (folder == null || !FolderDAO.get().isReadAllowed(folderId, user.getId()))
				throw new AccessDeniedException("You can't read this folder.");
			Optional<SearchFolderRule> rule = folder.getType() == HealdanConstants.FOLDER_TYPE_SEARCH
					? SearchFolderRuleDAO.get().find(folderId)
					: Optional.empty();
			if (rule.isEmpty())
				throw new HealdanRuleException("That folder isn't a search folder.");
			return toGUI(folder, rule.get());
		});
	}

	@Override
	public long create(long parentId, GUISearchFolderRule rule) throws ServerException {
		User user = session().getUser();
		return call("create the search folder",
				() -> SearchFolderService.get().create(parentId, rule.getName(), toRule(rule), user).getId());
	}

	@Override
	public void update(GUISearchFolderRule rule) throws ServerException {
		User user = session().getUser();
		call("save the search folder", () -> {
			SearchFolderService.get().update(rule.getFolderId(), rule.getName(), toRule(rule), user);
			return null;
		});
	}

	@Override
	public List<Long> evaluate(long folderId) throws ServerException {
		User user = session().getUser();
		return call("list the search folder", () -> {
			List<Long> ids = SearchFolderService.get().evaluate(folderId, user);
			// Newest first, so the cap keeps the most recent
			return new ArrayList<>(ids.subList(0, Math.min(ids.size(), MAX_LISTED)));
		});
	}

	@Override
	public List<String> classes() throws ServerException {
		session();
		return call("list the Classes", () -> new ArrayList<>(ProfileSchema.get().classOptions()));
	}

	@Override
	public List<String> subclasses(String docClass) throws ServerException {
		session();
		return call("list the Subclasses", () -> new ArrayList<>(ProfileSchema.get().subclassOptions(docClass)));
	}

	private static SearchFolderRule toRule(GUISearchFolderRule rule) throws HealdanRuleException {
		SearchFolderRule.Kind kind;
		try {
			kind = SearchFolderRule.Kind.valueOf(StringUtils.defaultString(rule.getKind()));
		} catch (IllegalArgumentException e) {
			throw new HealdanRuleException("Unknown kind: " + rule.getKind());
		}
		return new SearchFolderRule(rule.getFolderId(), rule.getDays(), rule.isOnlyMine(), kind,
				StringUtils.trimToNull(rule.getDocClass()), StringUtils.trimToNull(rule.getDocSubclass()));
	}

	private static GUISearchFolderRule toGUI(Folder folder, SearchFolderRule rule) {
		GUISearchFolderRule gui = new GUISearchFolderRule();
		gui.setFolderId(folder.getId());
		gui.setName(folder.getName());
		gui.setDays(rule.days());
		gui.setOnlyMine(rule.onlyMine());
		gui.setKind(rule.kind().name());
		gui.setDocClass(rule.docClass());
		gui.setDocSubclass(rule.docSubclass());
		return gui;
	}
}

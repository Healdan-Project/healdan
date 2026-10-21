package com.logicaldoc.healdan.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.BookmarkDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.Search;
import com.logicaldoc.core.searchengine.SearchException;
import com.logicaldoc.core.searchengine.SearchOptions;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.gui.common.client.beans.GUIValue;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIFolderRole;
import com.logicaldoc.gui.frontend.client.healdan.beans.GUIMatter;
import com.logicaldoc.gui.frontend.client.healdan.services.HealdanMatterService;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.ClientMatterRef;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDAO;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.matter.MatterNumbering;
import com.logicaldoc.healdan.matter.MatterService;
import com.logicaldoc.healdan.matter.NewMatter;
import com.logicaldoc.healdan.navigation.MatterEntry;
import com.logicaldoc.healdan.navigation.MyMatters;
import com.logicaldoc.healdan.navigation.RecentMatters;
import com.logicaldoc.healdan.setting.HealdanSettings;

import jakarta.servlet.annotation.WebServlet;

/**
 * The New and Edit Matter dialogs' server side (spec 5.3), what the folder
 * menu needs to know about a folder, and My Matters and Recent Matters for the
 * left pane (spec 8.2). Creating and editing matters, and the lists the
 * dialogs pick from, are for administrators only: the refusal comes before
 * {@link MatterService} is asked, so it is an access-denied error. The
 * left-pane lists are each user's own.
 */
@WebServlet("/frontend/healdanmatter")
public class HealdanMatterServiceImpl extends HealdanRemoteService implements HealdanMatterService {

	private static final long serialVersionUID = 1L;

	/** The most matters {@link #resolve} lists */
	static final int MAX_RESOLVED = 100;

	private User matterAdmin() throws ServerException {
		return admin("create or edit "
				+ HealdanSettings.get().matterLabelPlural().toLowerCase(Locale.ROOT));
	}

	@Override
	public GUIFolderRole describeFolder(long folderId) throws ServerException {
		User user = session().getUser();
		return call("describe the folder", () -> {
			Folder folder = FolderDAO.get().findById(folderId);
			// A folder the user can't read says nothing about itself, not even that it exists
			if (folder == null || !FolderDAO.get().isReadAllowed(folderId, user.getId()))
				throw new AccessDeniedException("You can't read this folder.");

			FolderPosition position = MatterLocator.get().locate(folder);
			GUIFolderRole role = new GUIFolderRole();
			role.setKind(position.kind().name());
			role.setMatterId(position.matter() != null ? position.matter().getId() : null);
			role.setTopLevel(position.isMatterTopLevel());
			role.setSearchFolder(position.searchFolder());
			role.setInMyMatters(position.kind() == FolderPosition.Kind.MATTER_WORKSPACE
					&& BookmarkDAO.get().findByUserIdAndFolderId(user.getId(), folderId) != null);
			return role;
		});
	}

	@Override
	public long suggestMatterNumber(long clientId) throws ServerException {
		matterAdmin();
		return call("suggest a number", () -> MatterNumbering.get().suggest(clientId));
	}

	@Override
	public List<GUIValue> listStores() throws ServerException {
		matterAdmin();
		return call("list the Stores", () -> values(MatterService.get().stores()));
	}

	@Override
	public List<GUIValue> listTemplates() throws ServerException {
		matterAdmin();
		return call("list the templates", () -> values(MatterService.get().templates()));
	}

	@Override
	public GUIMatter create(GUIMatter matter) throws ServerException {
		User user = matterAdmin();
		return call("create the matter", () -> {
			long templateId = matter.getTemplateId() != null ? matter.getTemplateId() : 0L;
			Matter created = MatterService.get()
					.create(new NewMatter(matter.getClientId(), matter.getNumber(), matter.getName(),
							matter.getDescription(), matter.getStoreId(), matter.getOwnerId(), templateId), user);
			return toGUI(created);
		});
	}

	@Override
	public GUIMatter getByFolder(long folderId) throws ServerException {
		matterAdmin();
		return call("read the matter", () -> {
			Matter matter = MatterDAO.get().findByFolderId(folderId);
			if (matter == null)
				throw new HealdanRuleException("This folder isn't a %s's workspace."
						.formatted(HealdanSettings.get().matterLabel().toLowerCase(Locale.ROOT)));
			return toGUI(matter);
		});
	}

	@Override
	public GUIMatter update(GUIMatter matter) throws ServerException {
		User user = matterAdmin();
		// The client and the number never change, whatever the request says
		return call("save the matter", () -> toGUI(MatterService.get().update(matter.getId(), matter.getName(),
				matter.getDescription(), matter.getOwnerId(), user)));
	}

	@Override
	public List<GUIMatter> myMatters() throws ServerException {
		User user = session().getUser();
		return call("list My Matters", () -> toGUI(MyMatters.get().list(user.getId())));
	}

	@Override
	public List<GUIMatter> recentMatters() throws ServerException {
		User user = session().getUser();
		return call("list Recent Matters", () -> toGUI(RecentMatters.get().list(user.getId())));
	}

	@Override
	public void addToMyMatters(long folderId) throws ServerException {
		User user = session().getUser();
		call("add to My Matters", () -> {
			MyMatters.get().add(user.getId(), folderId);
			return null;
		});
	}

	@Override
	public void removeFromMyMatters(long folderId) throws ServerException {
		User user = session().getUser();
		call("remove from My Matters", () -> {
			MyMatters.get().remove(user.getId(), folderId);
			return null;
		});
	}

	@Override
	public List<GUIMatter> resolve(String input) throws ServerException {
		User user = session().getUser();
		return call("find the matters", () -> {
			if (ClientMatterRef.parse(StringUtils.defaultString(input).strip()).isEmpty())
				return new ArrayList<>();
			// The Matters search reads the input and the user's rights: the same matters it would list
			SearchOptions options = Search.newOptions(HealdanConstants.SEARCH_MATTERS);
			options.setExpression(input);
			options.setUserId(user.getId());
			options.setMaxHits(MAX_RESOLVED);
			Search search = Search.get(options);
			try {
				search.search();
			} catch (SearchException e) {
				throw failure("search the matters", e);
			}

			List<GUIMatter> matters = new ArrayList<>();
			for (Hit hit : search.getHits()) {
				Matter matter = MatterDAO.get().findByFolderId(hit.getId());
				if (matter != null)
					matters.add(toGUI(matter));
			}
			return matters;
		});
	}

	/** A left-pane list's entries, with what a click and the entry need */
	private static List<GUIMatter> toGUI(List<MatterEntry> entries) {
		List<GUIMatter> matters = new ArrayList<>();
		for (MatterEntry entry : entries) {
			GUIMatter matter = new GUIMatter();
			matter.setId(entry.matterId());
			matter.setFolderId(entry.folderId());
			matter.setDisplay(entry.display());
			matter.setClientName(entry.clientName());
			matters.add(matter);
		}
		return matters;
	}

	/** The folders by name: the id as code, the name as value */
	private static List<GUIValue> values(List<Folder> folders) {
		List<Folder> sorted = new ArrayList<>(folders);
		sorted.sort(Comparator.comparing(Folder::getName, String.CASE_INSENSITIVE_ORDER));
		List<GUIValue> values = new ArrayList<>();
		for (Folder folder : sorted)
			values.add(new GUIValue(Long.toString(folder.getId()), folder.getName()));
		return values;
	}

	private static GUIMatter toGUI(Matter matter) throws PersistenceException {
		Client client = ClientDAO.get().findById(matter.getClientId());
		Folder workspace = FolderDAO.get().findById(matter.getFolderId());
		User owner = UserDAO.get().findById(matter.getOwnerId());

		GUIMatter gui = new GUIMatter();
		gui.setId(matter.getId());
		gui.setClientId(matter.getClientId());
		gui.setNumber(matter.getNumber());
		gui.setName(matter.getName());
		gui.setDescription(matter.getDescription());
		gui.setOwnerId(matter.getOwnerId());
		gui.setOwnerName(owner != null ? owner.getFullName() : null);
		gui.setTemplateId(matter.getTemplateId());
		gui.setFolderId(matter.getFolderId());
		gui.setStoreId(workspace != null ? workspace.getParentId() : 0L);
		if (client != null) {
			gui.setClientNumber(client.getNumber());
			gui.setClientName(client.getName());
			gui.setDisplay(MatterDisplay.format(client.getNumber(), matter.getNumber(), matter.getName()));
		}
		return gui;
	}
}

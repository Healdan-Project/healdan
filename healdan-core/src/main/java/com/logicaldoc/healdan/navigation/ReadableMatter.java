package com.logicaldoc.healdan.navigation;

import java.util.Comparator;
import java.util.Date;
import java.util.Optional;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDisplay;

/**
 * A live matter whose workspace a user can read: what both left-pane lists
 * show.
 *
 * @param matter the matter
 * @param client its client
 */
record ReadableMatter(Matter matter, Client client) {

	/**
	 * Display order: client number, then matter number, as numbers, so that
	 * 998.2 comes before 998.10
	 */
	static final Comparator<ReadableMatter> DISPLAY_ORDER = Comparator
			.comparingLong((ReadableMatter m) -> m.client().getNumber())
			.thenComparingLong(m -> m.matter().getNumber()).thenComparing(m -> m.matter().getName());

	/**
	 * @param position where a folder sits, as {@code MatterLocator} found it;
	 *        a folder it found is live, and so are the folders above it
	 * @param userId the user
	 * @return the matter the folder is or is in, if the folder is inside a
	 *         matter that has its {@code hd_matter} row and the user may read
	 *         the matter's workspace; empty otherwise
	 * @throws PersistenceException error at data layer
	 */
	static Optional<ReadableMatter> of(FolderPosition position, long userId) throws PersistenceException {
		Matter matter = position.matter();
		if (!position.isInsideMatter() || matter == null
				|| !FolderDAO.get().isReadAllowed(position.workspaceId(), userId))
			return Optional.empty();
		Client client = ClientDAO.get().findById(matter.getClientId());
		return client != null ? Optional.of(new ReadableMatter(matter, client)) : Optional.empty();
	}

	/**
	 * @param lastChange the user's latest change in the matter, null for none
	 * @return the matter's line in a list
	 */
	MatterEntry entry(Date lastChange) {
		return new MatterEntry(matter.getId(), matter.getFolderId(),
				MatterDisplay.format(client.getNumber(), matter.getNumber(), matter.getName()), lastChange);
	}
}

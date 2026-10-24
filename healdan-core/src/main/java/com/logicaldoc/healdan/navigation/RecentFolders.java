package com.logicaldoc.healdan.navigation;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * Recent folders (spec 6.2): the folders inside matters where the user created
 * or changed a document within the last
 * {@link HealdanSettings#recentMattersDays()} days, newest first, at most
 * {@value #MAX_ENTRIES}. The query and the window are {@link RecentMatters}',
 * but each folder is listed by itself. Only live, readable folders inside a
 * matter are listed: not a matter's workspace, a search folder or an alias.
 */
@Component
public class RecentFolders {

	/** The most folders listed */
	public static final int MAX_ENTRIES = 20;

	/** The latest change of the user in each folder, inside the window */
	private static final String QUERY = """
			select ld_folderid, max(ld_date)
			  from ld_history
			 where ld_deleted = 0
			   and ld_userid = :user
			   and ld_event in (:events)
			   and ld_date >= :since
			   and ld_folderid is not null
			 group by ld_folderid
			""";

	private final MatterLocator locator;

	private final HealdanSettings settings;

	private final NamedParameterJdbcTemplate jdbc;

	@Autowired
	public RecentFolders(MatterLocator locator, HealdanSettings settings, DataSource dataSource) {
		this.locator = locator;
		this.settings = settings;
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static RecentFolders get() {
		return Context.get(RecentFolders.class);
	}

	/**
	 * @param userId the user
	 * @return the folders the user changed a document in within the window,
	 *         latest change first, at most {@value #MAX_ENTRIES}
	 * @throws PersistenceException error at data layer
	 */
	public List<RecentFolder> list(long userId) throws PersistenceException {
		long since = Math.max(0L, System.currentTimeMillis() - settings.recentMattersDays() * HealdanConstants.DAY);
		MapSqlParameterSource params = new MapSqlParameterSource("user", userId)
				.addValue("events", HealdanConstants.CHANGE_EVENT_KEYS).addValue("since", new Timestamp(since));
		List<RecentFolder> changes = new ArrayList<>(
				jdbc.query(QUERY, params, (rs, i) -> new RecentFolder(rs.getLong(1), rs.getTimestamp(2))));
		// Newest first, and a fixed order between equal times
		changes.sort(Comparator.comparingLong((RecentFolder c) -> c.lastChange().getTime())
				.thenComparingLong(RecentFolder::folderId).reversed());

		FolderDAO folders = FolderDAO.get();
		List<RecentFolder> entries = new ArrayList<>();
		for (RecentFolder change : changes) {
			if (entries.size() == MAX_ENTRIES)
				break;
			Folder folder = folders.findById(change.folderId());
			if (folder == null || folder.getDeleted() != 0 || folder.getType() == Folder.TYPE_ALIAS
					|| !folders.isReadAllowed(folder.getId(), userId))
				continue;
			FolderPosition position = locator.locate(folder);
			if (position.kind() == FolderPosition.Kind.IN_MATTER && !position.searchFolder())
				entries.add(change);
		}
		return entries;
	}

	/**
	 * A folder the user changed a document in
	 *
	 * @param folderId the folder
	 * @param lastChange the user's latest change in it
	 */
	public record RecentFolder(long folderId, Date lastChange) {
	}
}

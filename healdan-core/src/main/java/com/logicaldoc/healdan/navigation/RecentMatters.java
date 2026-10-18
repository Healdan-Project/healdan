package com.logicaldoc.healdan.navigation;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.FolderPosition;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.spring.Context;

/**
 * Recent Matters (spec 8.2): the matters where the user created or changed a
 * document in the last {@link HealdanSettings#recentMattersDays()} days, newest
 * first, at most {@value #MAX_ENTRIES}.
 * <p>
 * A change is one of {@link HealdanConstants#CHANGE_EVENTS} in CE's document
 * history, made by the user. Each history entry names the folder the document
 * was in, and {@link MatterLocator} tells the matter that folder is in now.
 * Only live matters whose workspace the user can read are listed.
 */
@Component
public class RecentMatters {

	/** The most matters listed */
	static final int MAX_ENTRIES = 20;

	private static final long DAY = Duration.ofDays(1).toMillis();

	private static final List<String> CHANGE_EVENT_KEYS = HealdanConstants.CHANGE_EVENTS.stream()
			.map(DocumentEvent::toKey).sorted().toList();

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
	public RecentMatters(MatterLocator locator, HealdanSettings settings, DataSource dataSource) {
		this.locator = locator;
		this.settings = settings;
		this.jdbc = new NamedParameterJdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static RecentMatters get() {
		return Context.get(RecentMatters.class);
	}

	/**
	 * @param userId the user
	 * @return the matters the user changed a document in within the window,
	 *         latest change first, at most {@value #MAX_ENTRIES}, each with the
	 *         user's latest change in it
	 * @throws PersistenceException error at data layer
	 */
	public List<MatterEntry> list(long userId) throws PersistenceException {
		// From the start of the epoch at the earliest, whatever the window
		long since = Math.max(0L, System.currentTimeMillis() - settings.recentMattersDays() * DAY);
		MapSqlParameterSource params = new MapSqlParameterSource("user", userId).addValue("events", CHANGE_EVENT_KEYS)
				.addValue("since", new Timestamp(since));
		List<FolderChange> changes = new ArrayList<>(jdbc.query(QUERY, params,
				(rs, i) -> new FolderChange(rs.getLong(1), rs.getTimestamp(2).getTime())));
		// Newest first, and a fixed order between equal times
		changes.sort(Comparator.comparingLong(FolderChange::last).thenComparingLong(FolderChange::folderId)
				.reversed());

		// The first folder met of a matter holds the matter's latest change. Each matter
		// is checked once: the later folders of a matter already met, listed or not, are skipped
		Set<Long> decided = new HashSet<>();
		List<MatterEntry> entries = new ArrayList<>();
		for (FolderChange change : changes) {
			if (entries.size() == MAX_ENTRIES)
				break;
			FolderPosition position = locator.locate(change.folderId());
			if (position.isInsideMatter() && decided.add(position.workspaceId()))
				ReadableMatter.of(position, userId)
						.ifPresent(matter -> entries.add(matter.entry(new Date(change.last()))));
		}
		return entries;
	}

	/**
	 * The user's latest change in a folder
	 *
	 * @param folderId the folder
	 * @param last when, in milliseconds
	 */
	private record FolderChange(long folderId, long last) {
	}
}

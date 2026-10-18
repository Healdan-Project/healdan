package com.logicaldoc.healdan.search;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.searchengine.Hit;
import com.logicaldoc.core.searchengine.Search;
import com.logicaldoc.core.searchengine.SearchException;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.matter.ClientMatterRef;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.util.spring.Context;

/**
 * Search {@link HealdanConstants#SEARCH_MATTERS}: the Matters scope (spec 5.1,
 * 8.1). The input is read as:
 * <ul>
 * <li>{@code client.matter}, such as {@code 998.1}: that matter;</li>
 * <li>a bare number: the matters with that number. Per client that is every
 * client's matter with it; system-wide numbers are unique, deleted matters
 * included, so the same lookup finds one;</li>
 * <li>anything else: the matters whose name or description contains it,
 * ignoring case.</li>
 * </ul>
 * The hits are the live matters' workspaces the user can read, lowest client
 * and matter number first, as folder hits whose folder is the workspace
 * itself, so that opening a hit's folder opens the matter.
 */
public class MatterSearch extends Search {

	private static final String QUERY = """
			select m.hd_folderid, c.hd_number, m.hd_number, m.hd_name, f.ld_name, f.ld_type, f.ld_description,
			       f.ld_creation, f.ld_lastmodified, f.ld_color
			  from hd_matter m
			  join hd_client c on c.ld_id = m.hd_clientid
			  join ld_folder f on f.ld_id = m.hd_folderid
			 where m.ld_deleted = 0
			   and f.ld_deleted = 0
			   and (%s)
			 order by c.hd_number, m.hd_number
			""";

	@Override
	protected void internalSearch() throws SearchException {
		String input = StringUtils.strip(options.getExpression());
		if (StringUtils.isEmpty(input))
			return;

		MapSqlParameterSource params = new MapSqlParameterSource();
		String condition = condition(input, params);
		List<Hit> found = new NamedParameterJdbcTemplate(Context.get(DataSource.class))
				.query(QUERY.formatted(condition), params, MatterSearch::toHit);

		FolderDAO folders = FolderDAO.get();
		try {
			for (Hit hit : found) {
				if (!folders.isReadAllowed(hit.getId(), options.getUserId()))
					continue;
				if (options.getMaxHits() > 0 && hits.size() >= options.getMaxHits()) {
					moreHitsPresent = true;
					break;
				}
				hits.add(hit);
			}
		} catch (PersistenceException e) {
			throw new SearchException(e);
		}
		estimatedHitsNumber = moreHitsPresent ? hits.size() + 1L : hits.size();
	}

	/**
	 * @param input what the user typed, trimmed and not empty
	 * @param params where to put the condition's parameters
	 * @return the SQL condition on the matter {@code m} and its client
	 *         {@code c} that selects the matters the input means
	 */
	protected String condition(String input, MapSqlParameterSource params) {
		Optional<ClientMatterRef> ref = ClientMatterRef.parse(input);
		if (ref.isEmpty()) {
			params.addValue("text", containing(input));
			return "lower(m.hd_name) like :text escape '!' or lower(m.hd_description) like :text escape '!'";
		}
		if (ref.get().isNumberOnly()) {
			params.addValue("matter", ref.get().client());
			return "m.hd_number = :matter";
		}
		params.addValue("client", ref.get().client()).addValue("matter", ref.get().matter());
		return "c.hd_number = :client and m.hd_number = :matter";
	}

	/**
	 * @param text what the user typed
	 * @return a {@code like} pattern, with {@code !} as the escape, matching
	 *         lower-case text that contains it as it is: its {@code %} and
	 *         {@code _} are not wildcards
	 */
	protected static String containing(String text) {
		String escaped = text.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
		return "%" + escaped + "%";
	}

	private static Hit toHit(ResultSet rs, int row) throws SQLException {
		long folderId = rs.getLong(1);
		Folder workspace = new Folder();
		workspace.setId(folderId);
		workspace.setName(rs.getString(5));
		workspace.setType(rs.getInt(6));

		Hit hit = new Hit();
		hit.setId(folderId);
		hit.setFolder(workspace);
		hit.setType("folder");
		hit.setFileName(MatterDisplay.format(rs.getLong(2), rs.getLong(3), rs.getString(4)));
		hit.setCustomId(Long.toString(folderId));
		hit.setComment(rs.getString(7));
		hit.setCreation(rs.getTimestamp(8));
		hit.setDate(rs.getTimestamp(9));
		hit.setColor(rs.getString(10));
		hit.setPublished(true);
		return hit;
	}
}

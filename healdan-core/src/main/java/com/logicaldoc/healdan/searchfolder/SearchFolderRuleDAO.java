package com.logicaldoc.healdan.searchfolder;

import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.logicaldoc.healdan.searchfolder.SearchFolderRule.Kind;
import com.logicaldoc.util.spring.Context;

/**
 * Reads and writes the rules of the search folders, {@code hd_searchfolder}.
 * JDBC on CE's data source, so it joins the caller's transaction.
 */
@Component
public class SearchFolderRuleDAO {

	private final JdbcTemplate jdbc;

	@Autowired
	public SearchFolderRuleDAO(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	/**
	 * @return the bean
	 */
	public static SearchFolderRuleDAO get() {
		return Context.get(SearchFolderRuleDAO.class);
	}

	/**
	 * @param folderId a search folder
	 * @return its rule; none if it has no rule
	 */
	public Optional<SearchFolderRule> find(long folderId) {
		return jdbc.query(
				"select hd_days, hd_onlymine, hd_kind, hd_docclass, hd_docsubclass from hd_searchfolder where hd_folderid = ?",
				(rs, i) -> new SearchFolderRule(folderId, rs.getInt(1), rs.getInt(2) == 1, Kind.valueOf(rs.getString(3)),
						rs.getString(4), rs.getString(5)),
				folderId).stream().findFirst();
	}

	/**
	 * Saves a rule, replacing the search folder's rule if it has one
	 *
	 * @param rule the rule
	 */
	public void save(SearchFolderRule rule) {
		int updated = jdbc.update(
				"update hd_searchfolder set hd_days = ?, hd_onlymine = ?, hd_kind = ?, hd_docclass = ?, hd_docsubclass = ? where hd_folderid = ?",
				rule.days(), rule.onlyMine() ? 1 : 0, rule.kind().name(), rule.docClass(), rule.docSubclass(),
				rule.folderId());
		if (updated == 0)
			jdbc.update(
					"insert into hd_searchfolder (hd_folderid, hd_days, hd_onlymine, hd_kind, hd_docclass, hd_docsubclass) values (?, ?, ?, ?, ?, ?)",
					rule.folderId(), rule.days(), rule.onlyMine() ? 1 : 0, rule.kind().name(), rule.docClass(),
					rule.docSubclass());
	}

	/**
	 * Gives a search folder the rule of another, replacing its own; none if the
	 * other has none
	 *
	 * @param fromFolderId the search folder to copy from
	 * @param toFolderId the search folder to copy to
	 */
	public void copy(long fromFolderId, long toFolderId) {
		jdbc.update("delete from hd_searchfolder where hd_folderid = ?", toFolderId);
		jdbc.update("""
				insert into hd_searchfolder (hd_folderid, hd_days, hd_onlymine, hd_kind, hd_docclass, hd_docsubclass)
				select ?, hd_days, hd_onlymine, hd_kind, hd_docclass, hd_docsubclass from hd_searchfolder where hd_folderid = ?
				""", toFolderId, fromFolderId);
	}
}

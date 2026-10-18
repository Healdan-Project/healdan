package com.logicaldoc.healdan.docnumber;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentListener;
import com.logicaldoc.util.spring.Context;

/**
 * Gives every new document its doc # and keeps it from ever changing (spec
 * §6.1–§6.3). The doc # lives in CE's {@code customId}. A new document gets
 * the counter's next number, whatever value came with it. For a stored
 * document the value in the database wins over the incoming one, so an update,
 * a check-in without one, or CE's own {@code <customId>.<id>} suffix on delete
 * leaves a plain number behind. The listener never refuses anything: it puts
 * values back silently. CE creates it by reflection, hence the public no-arg
 * constructor.
 */
public class DocNumberListener implements DocumentListener {

	/** CE's suffix on a deleted document's customId: {@code <customId>.<id>} */
	private static final Pattern DELETED = Pattern.compile("^(\\d+)\\.(\\d+)$");

	public DocNumberListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (document.getId() == 0L) {
			if (document.getDocRef() == null)
				document.setCustomId(Long.toString(DocNumberCounter.get().next()));
			return;
		}

		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		List<String> rows = jdbc.queryForList("select ld_customid from ld_document where ld_id = ?", String.class,
				document.getId());
		if (rows.isEmpty() || rows.get(0) == null)
			return;

		String stored = rows.get(0);
		Matcher deleted = DELETED.matcher(stored);
		if (deleted.matches() && Long.parseLong(deleted.group(2)) == document.getId())
			stored = deleted.group(1);

		if (!stored.equals(document.getCustomId()))
			document.setCustomId(stored);
	}

	@Override
	public void afterStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterFileStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void beforeCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterCheckin(Document document, DocumentHistory transaction, Map<String, Object> dictionary) {
		// Nothing to do
	}

	@Override
	public void afterSaveHistory(Document document, DocumentHistory event, Map<String, Object> dictionary) {
		// Nothing to do
	}
}

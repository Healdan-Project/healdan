package com.logicaldoc.healdan.docnumber;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * values back silently. A stored row with no doc # (a document that came before
 * Healdan) keeps none: the stored value wins and no number is invented for it. A
 * document with a preset id and no row yet is new and takes a number. CE creates it by reflection, hence the public no-arg
 * constructor.
 * <p>
 * A route that must know a new document's number before CE stores it, such
 * as filing a file that shows its doc ID (M3 spec section 9.7), takes it from
 * the counter with {@link #reserveNext()} and hands it to the create with
 * {@link #useReserved(long)}; the number still comes only from the counter.
 */
public class DocNumberListener implements DocumentListener {

	/** CE's suffix on a deleted document's customId: {@code <customId>.<id>} */
	private static final Pattern DELETED = Pattern.compile("^(\\d+)\\.(\\d+)$");

	/** The number reserved for the next new document stored on this thread */
	private static final ThreadLocal<Long> RESERVED = new ThreadLocal<>();

	public DocNumberListener() {
		// Instantiated by CE
	}

	@Override
	public void beforeStore(Document document, DocumentHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (document.getId() != 0L) {
			Stored stored = stored(document.getId());
			if (stored.present()) {
				if (!Objects.equals(stored.customId(), document.getCustomId()))
					document.setCustomId(stored.customId());
				return;
			}
		}

		// New, or inserted with a preset id and no row yet
		if (document.getDocRef() == null) {
			Long reserved = RESERVED.get();
			RESERVED.remove();
			document.setCustomId(Long.toString(reserved != null ? reserved : DocNumberCounter.get().next()));
		}
	}

	/**
	 * Takes the counter's next number for a document about to be created, so
	 * that the caller can write it into the file before CE stores it. Like
	 * any number taken, it is never given again: one whose create fails is
	 * lost, as a deleted document's is.
	 *
	 * @return the number
	 */
	public static long reserveNext() {
		return DocNumberCounter.get().next();
	}

	/**
	 * Has the next new document stored on this thread take a number from
	 * {@link #reserveNext()} instead of the counter's next. Use it around
	 * CE's create alone, in a try-with-resources: closing it drops the number
	 * if the create failed before taking it, so that it can't go to another
	 * document stored later on the thread.
	 *
	 * @param number the reserved number
	 * @return what drops the number when closed
	 */
	public static Reservation useReserved(long number) {
		RESERVED.set(number);
		return new Reservation();
	}

	/**
	 * A number handed to the next create on this thread; see
	 * {@link #useReserved(long)}
	 */
	public static final class Reservation implements AutoCloseable {

		private Reservation() {
		}

		@Override
		public void close() {
			RESERVED.remove();
		}
	}

	/**
	 * Puts the stored doc # in a value object that is about to be applied to a
	 * document, so that an update can neither change the doc # nor stamp another
	 * one into the version it writes before the store. Never refuses.
	 *
	 * @param documentId the document being updated
	 * @param docVO the value object, may be null
	 */
	public static void protect(long documentId, Document docVO) {
		if (docVO == null || documentId == 0L)
			return;
		Stored stored = stored(documentId);
		if (stored.present())
			docVO.setCustomId(stored.customId());
	}

	/**
	 * @param id a document id
	 * @return whether it has a row, and its doc # without CE's delete suffix
	 */
	private static Stored stored(long id) {
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));
		List<String> rows = jdbc.queryForList("select ld_customid from ld_document where ld_id = ?", String.class,
				id);
		if (rows.isEmpty())
			return new Stored(false, null);

		String value = rows.get(0);
		if (value != null) {
			Matcher deleted = DELETED.matcher(value);
			if (deleted.matches() && Long.parseLong(deleted.group(2)) == id)
				value = deleted.group(1);
		}
		return new Stored(true, value);
	}

	private record Stored(boolean present, String customId) {
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

package com.logicaldoc.healdan.token;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.util.spring.Context;

/**
 * Which of a user's computers holds a check-out made through the API (spec
 * section 9.4), so that one person's computers can't save over each other. A
 * device is the device id its tokens carry, not a token: signing in again on
 * the same computer keeps its check-outs.
 * <p>
 * Only the current check-out counts. A record speaks for the check-out it was
 * written for, and is ignored once the document is free, checked out to
 * someone else, or checked out again since it was last written, as the old
 * web page does without recording a device. A check-out with no record works
 * from any of the holder's devices, as in M2. Records are never deleted: the
 * next check-out through the API writes over a stale one, and the end of a
 * check-out through the API empties it but keeps its last save's id and the
 * file version that save stored.
 * <p>
 * Only the API asks; the old web page acts for the person on any computer.
 */
@Component
public class DeviceHolds {

	/** What the history says of a check-out moved to another of the user's devices */
	static final String MOVED = "Moved to another device";

	private final HoldDeviceDAO dao;

	/**
	 * A token as a record names it, revoked or not
	 *
	 * @param userId the token's user
	 * @param name what the device is called
	 */
	private record RecordedToken(long userId, String name) {
	}

	@Autowired
	public DeviceHolds(HoldDeviceDAO dao) {
		this.dao = dao;
	}

	/**
	 * @return the bean in the application context
	 */
	public static DeviceHolds get() {
		return Context.get(DeviceHolds.class);
	}

	/**
	 * Records that the document's check-out is held on the token's device,
	 * through the token, in the app. A token with no device records none,
	 * which any of the holder's devices may write to. Writes nothing when the
	 * record already says so.
	 *
	 * @param doc the document, as it is now: checked out to the token's user
	 * @param token the token of the device that holds it
	 * @param app the app the document is open in, or null
	 */
	public void record(Document doc, ApiToken token, String app) {
		record(doc, token, app, null);
	}

	/**
	 * As {@link #record(Document, ApiToken, String)}, after a save through
	 * the API, in the same write.
	 *
	 * @param doc the document, as it is now: checked out to the token's user,
	 *        and after a save, with the file it stored
	 * @param token the token of the device that holds it
	 * @param app the app the document is open in, or null
	 * @param saveId the id of the save just stored, which
	 *        {@link #lastSaveId(Document)} answers from now on, and the
	 *        document's file version {@link #lastSaveFileVersion(Document)};
	 *        null to keep the last ones
	 */
	public void record(Document doc, ApiToken token, String app, String saveId) {
		HoldDevice row = find(doc);
		if (row != null && upToDate(doc, row) && Objects.equals(row.getDevice(), token.getDevice())
				&& Objects.equals(row.getTokenId(), token.getId()) && Objects.equals(row.getApp(), app)
				&& (saveId == null || isLastSave(row, doc, saveId)))
			return;

		if (row == null)
			row = newRow(doc);
		row.setDevice(token.getDevice());
		row.setTokenId(token.getId());
		row.setApp(app);
		setLastSave(row, doc, saveId);
		store(row);
	}

	/**
	 * @param doc a document, as it is now
	 * @return the record of the device that holds its check-out; empty when
	 *         it isn't checked out, when no device holds it, or when the
	 *         record is older than the check-out
	 */
	public Optional<HoldDevice> current(Document doc) {
		HoldDevice row = find(doc);
		return row != null && row.getDevice() != null && upToDate(doc, row) ? Optional.of(row) : Optional.empty();
	}

	/**
	 * Answers only which device: whose check-out it is is the caller's to
	 * check.
	 *
	 * @param doc a document, as it is now
	 * @param token a token of the user who has it checked out
	 * @return true unless another device holds the check-out; true for a
	 *         check-out no device holds
	 */
	public boolean heldHere(Document doc, ApiToken token) {
		return current(doc).map(row -> row.getDevice().equals(token.getDevice())).orElse(true);
	}

	/**
	 * Moves the document's check-out to the token's device, and writes a
	 * check-out row in its history, so the move shows there.
	 *
	 * @param doc the document, as it is now
	 * @param token a token of the user who has it checked out
	 * @param app the app the document is opened in, or null
	 * @throws IllegalArgumentException if the document isn't checked out to
	 *         the token's user
	 */
	public void move(Document doc, ApiToken token, String app) {
		if (doc.getStatus() != DocumentStatus.CHECKEDOUT || !Long.valueOf(token.getUserId()).equals(doc.getLockUserId()))
			throw new IllegalArgumentException(
					"Document %d isn't checked out to the token's user, so it can't move".formatted(doc.getId()));

		DocumentHistory history = new DocumentHistory();
		history.setDocument(doc);
		history.setEvent(DocumentEvent.CHECKEDOUT);
		history.setComment(MOVED);
		try {
			// The token's session is the request's, as for the API's other check-outs
			Session session = token.getSid() != null ? SessionManager.get().get(token.getSid()) : null;
			if (session != null)
				history.setSession(session);
			else
				history.setUser(UserDAO.get().findById(token.getUserId()));
			DocumentHistoryDAO.get().store(history);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't record the move of document " + doc.getId(), e);
		}
		// After the history row, so the record is newer than the check-out it names
		record(doc, token, app);
	}

	/**
	 * Records that no device holds the document's check-out any more, when it
	 * ends through the API. The record's last save id and file version stay.
	 *
	 * @param doc the document
	 */
	public void clear(Document doc) {
		clear(doc, null);
	}

	/**
	 * As {@link #clear(Document)}, after a save through the API that ended
	 * the check-out, in the same write. A document with no record gets one
	 * that names no device, for the save's id.
	 *
	 * @param doc the document, after a save with the file it stored
	 * @param saveId the id of the save just stored, which
	 *        {@link #lastSaveId(Document)} answers from now on, and the
	 *        document's file version {@link #lastSaveFileVersion(Document)};
	 *        null to keep the last ones
	 */
	public void clear(Document doc, String saveId) {
		HoldDevice row = find(doc);
		boolean empty = row == null || row.getDevice() == null && row.getTokenId() == null && row.getApp() == null;
		if (empty && (saveId == null || row != null && isLastSave(row, doc, saveId)))
			return;

		if (row == null)
			row = newRow(doc);
		row.setDevice(null);
		row.setTokenId(null);
		row.setApp(null);
		setLastSave(row, doc, saveId);
		store(row);
	}

	/**
	 * Whatever the document's state: the id outlives the check-out it was
	 * saved in, so that a save on closing sent again is told too.
	 *
	 * @param doc a document
	 * @return the id of the last save stored through the API with one, or
	 *         null if none was
	 */
	public String lastSaveId(Document doc) {
		HoldDevice row = find(doc);
		return row != null ? row.getLastSaveId() : null;
	}

	/**
	 * A save sent again is told it's stored only while this is still the
	 * document's {@code fileVersion}. Once newer content is stored, by any
	 * route, telling it so would pass that content off as the save's.
	 *
	 * @param doc a document
	 * @return the file version the last save with an id stored, or null if
	 *         none was stored, or it was stored before this was kept (schema
	 *         version 5), when nothing says the file is still that one
	 */
	public String lastSaveFileVersion(Document doc) {
		HoldDevice row = find(doc);
		return row != null ? row.getLastSaveFileVersion() : null;
	}

	/**
	 * @param row a record
	 * @return what its device is called: the name of the token it was last
	 *         written through, revoked or not; null if it names none
	 */
	public String deviceName(HoldDevice row) {
		RecordedToken token = token(row.getTokenId());
		return token != null ? token.name() : null;
	}

	/**
	 * @return true if the document is checked out to the user of the record's
	 *         token, and no check-out came after the record was written
	 */
	private boolean upToDate(Document doc, HoldDevice row) {
		if (doc.getStatus() != DocumentStatus.CHECKEDOUT || doc.getLockUserId() == null || row.getTokenId() == null)
			return false;
		// The check-out changed hands, whatever the history kept
		RecordedToken token = token(row.getTokenId());
		if (token == null || token.userId() != doc.getLockUserId())
			return false;
		Date latest = latestCheckout(doc.getId());
		return latest == null || latest.getTime() <= row.getLastModified().getTime();
	}

	/**
	 * @return when the document was last checked out, by anyone, or null if
	 *         its history doesn't say. Read through Hibernate, as the record's
	 *         date is, so both are in the same time zone.
	 */
	private static Date latestCheckout(long docId) {
		try {
			List<DocumentHistory> latest = DocumentHistoryDAO.get().findByWhere(
					"_entity.docId = :docId and _entity.event = :event",
					Map.of("docId", docId, "event", DocumentEvent.CHECKEDOUT.toKey()),
					"_entity.date desc, _entity.id desc", 1);
			return latest.isEmpty() ? null : latest.get(0).getDate();
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the history of document " + docId, e);
		}
	}

	private RecordedToken token(Long id) {
		if (id == null)
			return null;
		try {
			return dao.query("select hd_userid, hd_name from hd_api_token where ld_id = :id", Map.of("id", id),
					(rs, i) -> new RecordedToken(rs.getLong(1), rs.getString(2)), null).stream().findFirst()
					.orElse(null);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read a device token", e);
		}
	}

	/**
	 * @return true if the record already names the save, and the file it
	 *         stored as the document's now
	 */
	private static boolean isLastSave(HoldDevice row, Document doc, String saveId) {
		return saveId.equals(row.getLastSaveId()) && Objects.equals(doc.getFileVersion(), row.getLastSaveFileVersion());
	}

	/**
	 * Keeps the save's id with the file it stored, the document's now; a null
	 * id keeps the last ones
	 */
	private static void setLastSave(HoldDevice row, Document doc, String saveId) {
		if (saveId == null)
			return;
		row.setLastSaveId(saveId);
		row.setLastSaveFileVersion(doc.getFileVersion());
	}

	private static HoldDevice newRow(Document doc) {
		HoldDevice row = new HoldDevice();
		row.setTenantId(doc.getTenantId());
		row.setDocId(doc.getId());
		return row;
	}

	private HoldDevice find(Document doc) {
		try {
			return dao.findByDocId(doc.getId());
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read which device holds document " + doc.getId(), e);
		}
	}

	private void store(HoldDevice row) {
		try {
			dao.store(row);
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't record which device holds document " + row.getDocId(), e);
		}
	}
}

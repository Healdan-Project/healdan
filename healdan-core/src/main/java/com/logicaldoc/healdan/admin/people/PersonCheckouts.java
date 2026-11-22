// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.DocumentStatus;
import com.logicaldoc.healdan.matter.Client;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.matter.MatterDisplay;
import com.logicaldoc.healdan.matter.MatterLocator;
import com.logicaldoc.healdan.token.DeviceHolds;

/**
 * The documents a person has checked out, as the User app's Checked Out to Me reads them for its reader, but every
 * one of them, as an administrator reads every document: documents with a doc # in a Store, no shortcut, locks left
 * out.
 */
final class PersonCheckouts {

	private PersonCheckouts() {
	}

	/**
	 * @param userId a person
	 * @return their check-outs, the latest first, then by doc #, highest first
	 */
	static List<CheckedOut> of(long userId) {
		try {
			List<CheckedOut> held = new ArrayList<>();
			for (Document doc : shown(userId)) {
				Matter matter = MatterLocator.get().locate(doc.getFolder().getId()).matter();
				CheckedOut.Matter brief = null;
				if (matter != null) {
					Client client = ClientDAO.get().findById(matter.getClientId());
					brief = new CheckedOut.Matter(matter.getId(),
							MatterDisplay.number(client.getNumber(), matter.getNumber()), matter.getName());
				}
				DeviceHolds.Held device = DeviceHolds.get().held(doc).orElse(null);
				held.add(new CheckedOut(Long.parseLong(doc.getCustomId()), doc.getFileVersion(), doc.getFileName(),
						brief, since(doc), device != null ? device.name() : null,
						device != null ? device.row().getDevice() : null,
						device != null ? device.row().getApp() : null));
			}
			held.sort(Comparator.comparing(CheckedOut::since, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(Comparator.comparingLong(CheckedOut::docNumber).reversed()));
			return held;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't list the check-outs of user " + userId, e);
		}
	}

	/**
	 * @param userId a person
	 * @return how many documents {@link #of} lists
	 */
	static int count(long userId) {
		return shown(userId).size();
	}

	/**
	 * @return the documents checked out to the person that the API shows: with a doc # and in a Store, no shortcut
	 */
	private static List<Document> shown(long userId) {
		try {
			List<Document> shown = new ArrayList<>();
			for (Document partial : DocumentDAO.get().findByLockUserAndStatus(userId, DocumentStatus.CHECKEDOUT)) {
				// The partial documents the DAO returns are only good for their ids
				Document doc = DocumentDAO.get().findById(partial.getId());
				if (doc != null && doc.getDeleted() == 0 && doc.getDocRef() == null && numbered(doc.getCustomId())
						&& MatterLocator.get().locate(doc.getFolder().getId()).isUnderAStore())
					shown.add(doc);
			}
			return shown;
		} catch (PersistenceException e) {
			throw new IllegalStateException("Can't read the check-outs of user " + userId, e);
		}
	}

	/** Whether a doc # is one the API shows: digits, at most 18 */
	private static boolean numbered(String customId) {
		return customId != null && !customId.isEmpty() && customId.length() <= 18
				&& customId.chars().allMatch(c -> c >= '0' && c <= '9');
	}

	/** When the holder checked the document out: their latest check-out of it */
	private static Instant since(Document doc) throws PersistenceException {
		if (doc.getLockUserId() == null)
			return null;
		// A missing date last, where PostgreSQL would put it first in a descending sort
		List<DocumentHistory> latest = DocumentHistoryDAO.get().findByWhere(
				"_entity.docId = :docId and _entity.event = :event and _entity.userId = :userId",
				Map.of("docId", doc.getId(), "event", DocumentEvent.CHECKEDOUT.toKey(), "userId", doc.getLockUserId()),
				"_entity.date desc nulls last, _entity.id desc", 1);
		return latest.isEmpty() || latest.get(0).getDate() == null ? null : latest.get(0).getDate().toInstant();
	}
}

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentEvent;
import com.logicaldoc.core.document.DocumentHistory;
import com.logicaldoc.core.document.DocumentHistoryDAO;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.VersionDAO;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.util.spring.Context;

/**
 * Reading a document, recorded as CE's {@code event.viewed} in the document's history (Admin app spec section 8.8),
 * for the Audit Trail: a preview served, and a filed email's body or an attachment of it shown in place, which count
 * as reading the email. The routes call {@link #viewed} as they are about to answer with what's read; an answer that
 * shows nothing of it, a {@code 202}, a refusal or a {@code 404}, records nothing.
 * <p>
 * A view is recorded once per person and document in {@link #WINDOW}, so going through a list twice or reloading
 * doesn't record it again. The check is in memory, as Healdan runs one node, so a restart can record one more. The
 * memory is bounded: what's older than the window is dropped as a view is recorded, and past
 * {@link #MAX_REMEMBERED} the oldest go, which can only record a view again sooner.
 * <p>
 * The row is written in a transaction of its own, so a caller's transaction never holds it, and a failure to write it
 * never fails the read, whatever it is but a {@link VirtualMachineError}: it's logged at warn with the document's id
 * and the failure's class, and isn't remembered, so the next read tries again.
 */
@Component
public class DocumentViews {

	/** How long a person's view of a document isn't recorded again */
	static final Duration WINDOW = Duration.ofMinutes(30);

	/** The most views remembered at once */
	static final int MAX_REMEMBERED = 20_000;

	private static final Logger log = LoggerFactory.getLogger(DocumentViews.class);

	/** For tests: the clock views are timed by, in nanoseconds, which only goes forward */
	volatile LongSupplier nanoTime = System::nanoTime;

	/** For tests: run in a write's transaction just before its row is stored; one that throws fails the write */
	volatile Runnable beforeStore = () -> {
	};

	/** Who viewed what, and when, oldest first; guarded by itself */
	private final LinkedHashMap<Viewer, Long> recent = new LinkedHashMap<>() {

		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<Viewer, Long> eldest) {
			return size() > MAX_REMEMBERED;
		}
	};

	private final TransactionTemplate own;

	@Autowired
	public DocumentViews(PlatformTransactionManager transactions) {
		this.own = new TransactionTemplate(transactions);
		own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * @return the bean in the application context
	 */
	public static DocumentViews get() {
		return Context.get(DocumentViews.class);
	}

	/**
	 * Records that a person read a document, unless they did within {@link #WINDOW}. It never throws, but for the
	 * virtual machine's own errors ({@link VirtualMachineError}), which go on up.
	 *
	 * @param document the document read; for an email's attachment, the email
	 * @param user who read it
	 * @param session the session they read it in, which gives the row its session and address
	 * @param fileVersion the file version read; the current file's when null
	 */
	public void viewed(Document document, User user, Session session, String fileVersion) {
		if (document == null || user == null)
			return;
		Viewer viewer = new Viewer(user.getId(), document.getId());
		Long claimed = claim(viewer);
		if (claimed == null)
			return;
		try {
			own.executeWithoutResult(status -> {
				try {
					DocumentHistory history = history(document, user, session, fileVersion);
					beforeStore.run();
					DocumentHistoryDAO.get().store(history);
				} catch (PersistenceException e) {
					throw new NotRecorded(e);
				}
			});
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable e) {
			// Any other failure, an error such as a class a plugin's zip left out included, as Cached.read draws the
			// line: the write fails, and the read it rides on doesn't
			forget(viewer, claimed);
			Throwable failure = e instanceof NotRecorded && e.getCause() != null ? e.getCause() : e;
			log.warn("Couldn't record a view of document {}: {}", document.getId(), failure.getClass().getName());
			log.debug("Couldn't record a view of document {}", document.getId(), e);
		}
	}

	/**
	 * @return the row: the person, the session and its address, the document's folder and path, and the version whose
	 *         file was read
	 */
	private static DocumentHistory history(Document document, User user, Session session, String fileVersion)
			throws PersistenceException {
		DocumentHistory history = new DocumentHistory();
		history.setPath(FolderDAO.get().computePathExtended(document.getFolder().getId()));
		history.setDocument(document);
		history.setUser(user);
		history.setSession(session);
		history.setEvent(DocumentEvent.VIEWED);
		if (fileVersion != null && !fileVersion.equals(document.getFileVersion())) {
			// An earlier file: the version that made it, with the name it had then
			history.setFileVersion(fileVersion);
			Version made = VersionDAO.get().findByFileVersion(document.getId(), fileVersion);
			if (made != null) {
				history.setVersion(made.getVersion());
				if (StringUtils.isNotBlank(made.getFileName()))
					history.setFilename(made.getFileName());
			}
		}
		return history;
	}

	/**
	 * Remembers a view about to be recorded, unless one of the same document by the same person is within
	 * {@link #WINDOW}, and drops what's older.
	 *
	 * @return when it's remembered, to {@link #forget} it by; null if one is within the window
	 */
	Long claim(long userId, long docId) {
		return claim(new Viewer(userId, docId));
	}

	private Long claim(Viewer viewer) {
		synchronized (recent) {
			// Read inside the lock, so the map stays in the order of its times
			long now = nanoTime.getAsLong();
			long window = WINDOW.toNanos();
			for (Iterator<Long> times = recent.values().iterator(); times.hasNext();) {
				if (now - times.next() < window)
					break;
				times.remove();
			}
			if (recent.containsKey(viewer))
				return null;
			recent.put(viewer, now);
			return now;
		}
	}

	/** Forgets a view that wasn't recorded, unless it has been remembered again since */
	private void forget(Viewer viewer, long claimed) {
		synchronized (recent) {
			recent.remove(viewer, claimed);
		}
	}

	/** @return how many views are remembered */
	int remembered() {
		synchronized (recent) {
			return recent.size();
		}
	}

	private record Viewer(long userId, long docId) {
	}

	/** A failure of CE's DAO, carried out of the write's transaction */
	private static final class NotRecorded extends RuntimeException {

		private static final long serialVersionUID = 1L;

		NotRecorded(PersistenceException cause) {
			super(cause);
		}
	}
}

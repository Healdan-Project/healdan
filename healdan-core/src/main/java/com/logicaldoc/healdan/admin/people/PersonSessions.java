// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.people;

import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.util.crypt.Encrypter;

/**
 * Closes a person's open LogicalDOC sessions on this node: the web page's, the Classic Interface's and those a device
 * token or an API key opened, whether they signed in as themselves or as another account that lets them act as it
 * ({@code carol>bob}). Disable, Reset Password and Sign Out Everywhere close them once their change has committed, and
 * so does linking an account to a sign-in provider ({@code SignInAccounts}).
 * <p>
 * A session is closed by its id ({@link SessionManager#kill}), never through the request that asks, so the
 * administrator's own session stays open unless it's the person's. Only the sessions in this node's pool can be found.
 */
public final class PersonSessions {

	private static final Logger log = LoggerFactory.getLogger(PersonSessions.class);

	/**
	 * How many sessions a close closed, and how many it couldn't check or close
	 *
	 * @param closed the sessions closed
	 * @param failed the sessions that couldn't be checked or closed, or 1 when the sessions couldn't be read
	 */
	public record Closed(int closed, int failed) {
	}

	private PersonSessions() {
	}

	/**
	 * Closes every open session of the account on this node, whoever impersonated it, and every one the person opened
	 * as another account. It never throws: a session that can't be checked or closed is logged at debug and left, and
	 * the others are closed all the same.
	 *
	 * @param userId the account's id
	 * @param username the account's username, as stored
	 * @return how many sessions it closed
	 */
	public static int closeAll(long userId, String username) {
		Closed closed = close(userId, username, Set.of());
		if (closed.failed() > 0)
			log.warn("Couldn't check or close {} of the sessions of account {}; an administrator can kill them in the "
					+ "Sessions list", closed.failed(), userId);
		return closed.closed();
	}

	/**
	 * Closes this node's open sessions that one of the keys opened, and, given an account, every one of its own and
	 * every one it opened as another account. LogicalDOC's pool keeps each session's key as it was given, so a session
	 * is matched by the part before an impersonation's {@code >}, as its chain reads it: {@code <key>>bob} opens a
	 * session as {@code bob} with the key's owner as the impersonator. A session opened as another account names the
	 * account that opened it by its username, as stored. Each session is checked and closed on its own, so one that
	 * fails doesn't keep the others open. It never throws.
	 *
	 * @param userId the account whose sessions all close, or null for only the keys' sessions
	 * @param username the username, as stored, of the account whose sessions as another account close too; null for
	 *        none
	 * @param hashes the SHA-256 of keys whose sessions close, possibly none
	 * @return how many it closed, and how many it couldn't check or close
	 */
	public static Closed close(Long userId, String username, Set<String> hashes) {
		Objects.requireNonNull(hashes, "hashes");
		int closed = 0;
		int failed = 0;
		try {
			SessionManager sessions = SessionManager.get();
			// newSession holds this lock while it signs in, so a sign-in that read the account or its key before the
			// commit has its session in the pool by now, and one that starts later finds the password or key gone.
			// Healdan never decides a sign-in inside newSession, so a caller's lock, held here, is never taken the
			// other way round.
			synchronized (sessions) {
				for (Session session : sessions.getSessions()) {
					try {
						if (session.isOpen() && (accounts(session, userId) || openedAsAnother(session, username)
								|| openedWith(session, hashes))) {
							sessions.kill(session.getSid());
							closed++;
						}
					} catch (RuntimeException e) {
						failed++;
						log.debug("A session couldn't be checked or closed", e);
					}
				}
			}
		} catch (RuntimeException e) {
			failed++;
			log.debug("The sessions couldn't be read", e);
		}
		return new Closed(closed, failed);
	}

	/** Whether the session is the account's own: it signed in as it, whoever impersonated it */
	private static boolean accounts(Session session, Long userId) {
		return userId != null && session.getUser() != null && session.getUserId() == userId;
	}

	/** Whether the account opened the session as another account: the session names it as its impersonator */
	private static boolean openedAsAnother(Session session, String username) {
		return username != null && username.equals(session.getImpersonator());
	}

	/**
	 * Whether one of the keys opened the session. A session opened with a password has no key, neither as given nor
	 * hashed, so it's never one.
	 */
	private static boolean openedWith(Session session, Set<String> hashes) {
		if (hashes.isEmpty())
			return false;
		String key = session.getDecodedKey();
		if (key == null) {
			// The set refuses to look a null up
			String hash = session.getKey();
			return hash != null && hashes.contains(hash);
		}
		if (key.contains(">"))
			key = StringUtils.substringBefore(key, ">").trim();
		try {
			return hashes.contains(Encrypter.encryptSHA256(key));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}

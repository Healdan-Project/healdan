package com.logicaldoc.healdan.web;

import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.gui.common.client.InvalidSessionServerException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Session validation shared by the Healdan servlets.
 */
public final class HealdanSessions {

	private HealdanSessions() {
	}

	/**
	 * Retrieves the open session of the request and renews it.
	 * 
	 * @param request the current request
	 * @return the session
	 * @throws InvalidSessionServerException the session is missing, invalid or
	 *         expired
	 */
	public static Session validate(HttpServletRequest request) throws InvalidSessionServerException {
		String sid = SessionManager.get().getSessionId(request);
		Session session = SessionManager.get().get(sid);
		if (session == null)
			throw new InvalidSessionServerException("Invalid Session");
		if (!SessionManager.get().isOpen(sid))
			throw new InvalidSessionServerException("Invalid or Expired Session");
		SessionManager.get().renew(sid);
		return session;
	}
}

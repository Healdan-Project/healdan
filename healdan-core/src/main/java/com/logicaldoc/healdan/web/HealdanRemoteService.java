package com.logicaldoc.healdan.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gwt.user.server.rpc.jakarta.RemoteServiceServlet;
import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.gui.common.client.AccessDeniedException;
import com.logicaldoc.gui.common.client.InvalidSessionServerException;
import com.logicaldoc.gui.common.client.ServerException;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.rules.FilingRules;

/**
 * What Healdan's GWT RPC servlets share: the session of the request, the
 * admin check, and turning errors into messages the GUI shows.
 */
public abstract class HealdanRemoteService extends RemoteServiceServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger log = LoggerFactory.getLogger(HealdanRemoteService.class);

	/**
	 * @return the session of the current request
	 * @throws InvalidSessionServerException the session is missing, invalid or
	 *         expired
	 */
	protected Session session() throws InvalidSessionServerException {
		return HealdanSessions.validate(getThreadLocalRequest());
	}

	/**
	 * @param what what only an administrator may do, for the refusal, such as
	 *        "manage clients"
	 * @return the session's user, an administrator
	 * @throws ServerException an {@link AccessDeniedException} if the user
	 *         isn't an administrator, or an invalid session
	 */
	protected User admin(String what) throws ServerException {
		User user = session().getUser();
		try {
			if (!FilingRules.get().isAdmin(user))
				throw new AccessDeniedException("Only an administrator can %s.".formatted(what));
		} catch (PersistenceException e) {
			throw failure("check the user's groups", e);
		}
		return user;
	}

	/** Work that may refuse with a {@link HealdanRuleException} */
	@FunctionalInterface
	protected interface Work<T> {
		T run() throws PersistenceException, ServerException;
	}

	/**
	 * Runs the work, passing a refusal's message on to the GUI as it is
	 * 
	 * @param what what the work does, for the log, such as "save the matter"
	 * @param work the work
	 * @return what the work returns
	 * @throws ServerException a refusal, with its message, or an unexpected
	 *         error
	 */
	protected static <T> T call(String what, Work<T> work) throws ServerException {
		try {
			return work.run();
		} catch (HealdanRuleException e) {
			throw new ServerException(e.getMessage());
		} catch (PersistenceException | RuntimeException e) {
			throw failure(what, e);
		}
	}

	/**
	 * Logs an unexpected error
	 *
	 * @param what what failed, such as "load the settings"
	 * @param e the error
	 * @return the exception to throw to the GUI
	 */
	protected static ServerException failure(String what, Exception e) {
		log.error("Cannot {}", what, e);
		return new ServerException("Cannot %s: %s".formatted(what, e.getMessage()));
	}
}

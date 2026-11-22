// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.app;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.WebSessionRoute;
import com.logicaldoc.healdan.security.AccessChecks;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves the built Admin app, which sits in this jar under {@link #ROOT}, at <code>/admin/</code>, to administrators
 * only: members of the <code>admin</code> group, asked again at every request, so someone taken out of it loses the
 * app at their next load (admin spec 6.1). The check comes before anything reads the path, so whoever isn't an
 * administrator gets none of the app's bytes and learns nothing of what its build holds:
 * <ul>
 * <li>signed out, a page remembers its address and goes to <code>login.jsp</code>, and a file (anything under
 * {@link #ASSETS}, or a name with an extension the build would have) is an empty <code>401</code>;</li>
 * <li>signed in and not an administrator, every path is the same <code>403</code>, the User app's own page, which
 * draws its no-access panel;</li>
 * <li>an administrator gets the page, <code>index.html</code>, for any address of the app, and a file or
 * <code>404</code>;</li>
 * <li>if the check itself fails, as when the database doesn't answer, every path is the same <code>503</code>, a line
 * of plain text to try again, which tells nobody they've lost access and keeps the return path.</li>
 * </ul>
 * Which paths are files is decided by the path alone, never by what the build holds. Nothing is kept by a proxy:
 * hashed files are <code>private</code>, and the page and every refusal are <code>no-store</code>.
 * <p>
 * Its policy is the User app's without a worker ({@link #POLICY}). It allows a frame of the server's own, as the User
 * app's does, and nothing else: the app's downloads, the Server Log's zip and the Audit Trail's CSV, go through a
 * hidden frame at the API's address, a plain navigation that carries the web session the API takes (ruling P5-R38).
 */
@WebServlet(urlPatterns = "/admin/*", loadOnStartup = 3)
public class AdminAppServlet extends BuiltAppServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger log = LoggerFactory.getLogger(AdminAppServlet.class);

	public static final String ROOT = "healdan/app/admin/";

	/**
	 * The User app's {@link AppServlet#POLICY}, without PDF.js's worker: a frame of the server's own is allowed, for
	 * the downloads' hidden frame
	 */
	public static final String POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
			+ "img-src 'self' data: blob:; font-src 'self' data:; worker-src 'none'; connect-src 'self'; "
			+ "frame-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

	@Override
	String root() {
		return ROOT;
	}

	@Override
	String policy() {
		return POLICY;
	}

	@Override
	String assetCacheControl() {
		return "private, max-age=31536000, immutable";
	}

	@Override
	String notBuilt() {
		return "The Admin app wasn't built into this server.";
	}

	/**
	 * The person the web session belongs to, as the API's web-session route finds them, and whether they're an
	 * administrator, asked afresh: nothing is kept between requests. A check that fails lets nobody in, and asks them
	 * to try again.
	 */
	@Override
	Access access(HttpServletRequest request) {
		try {
			Optional<User> user = WebSessionRoute.session(request).map(WebSessionRoute::user);
			if (user.isEmpty())
				return Access.SIGN_IN;
			return isAdmin(user.get()) ? Access.OPEN : Access.REFUSED;
		} catch (PersistenceException | RuntimeException e) {
			log.warn("Can't tell whether a request to the Admin app is an administrator's, so it is asked to try again",
					e);
			return Access.UNCHECKED;
		}
	}

	/** Whether the user is an administrator, as Healdan's rules decide; tests make it fail */
	boolean isAdmin(User user) throws PersistenceException {
		return AccessChecks.isAdmin(user);
	}

	/**
	 * By the path alone, so a signed-out answer says nothing of what the build holds: anything under {@link #ASSETS},
	 * or a last segment with an extension of the build's media types. An address of the app, such as
	 * <code>/matters/1001.1</code>, and a path that could leave {@link #ROOT}, are the app's page.
	 */
	@Override
	String file(String pathInfo) {
		String path = relative(pathInfo).orElse(null);
		if (path == null)
			return null;
		String last = lastSegment(pathInfo);
		if (path.startsWith(ASSETS) && !last.isEmpty())
			return path;
		int dot = last.lastIndexOf('.');
		return dot >= 0 && TYPES.containsKey(last.substring(dot + 1)) ? path : null;
	}
}

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.web;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Gives <code>login.jsp</code> a <code>RequestedUrl</code> when the HTTP session has none waiting, so a plain sign-in
 * lands where <code>ui.start</code> says. Only a return path into the User app waits ({@link ReturnPath#waiting}); an
 * earlier landing, which nothing clears after its sign-in, is replaced. With <code>?switch</code> it does nothing, so
 * <code>login.jsp</code>'s own clearing of the value still works (M6 spec 8.4).
 */
@WebFilter("/login.jsp")
public class LoginLandingFilter implements Filter {

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (servletRequest instanceof HttpServletRequest request && request.getParameter("switch") == null) {
			HttpSession session = request.getSession(false);
			if (session == null || !ReturnPath.waiting(session.getAttribute(ReturnPath.ATTRIBUTE), request))
				request.getSession(true).setAttribute(ReturnPath.ATTRIBUTE, Landing.start(request));
		}
		chain.doFilter(servletRequest, response);
	}
}

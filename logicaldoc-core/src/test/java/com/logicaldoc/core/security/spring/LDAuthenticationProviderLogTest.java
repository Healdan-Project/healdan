// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.core.security.spring;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.logicaldoc.core.AbstractCoreTestCase;
import com.logicaldoc.core.LogCapture;
import com.logicaldoc.util.servlet.MockServletRequest;

/**
 * The form sign-in's debug log names the user, never the key that came with
 * the request: an API key or a second factor, both secrets. CE logged it raw.
 */
public class LDAuthenticationProviderLogTest extends AbstractCoreTestCase {

    private static final String KEY = "ld-0123456789abcdef-secret";

    @After
    @Override
    public void tearDown() throws java.io.IOException {
        RequestContextHolder.resetRequestAttributes();
        super.tearDown();
    }

    @Test
    public void testDebugLogLeavesTheKeyOut() {
        MockServletRequest request = new MockServletRequest();
        request.setParameter("key", KEY);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        try (LogCapture log = new LogCapture(LDAuthenticationProvider.class)) {
            try {
                new LDAuthenticationProvider()
                        .authenticate(new UsernamePasswordAuthenticationToken("admin", "wrong password"));
            } catch (RuntimeException e) {
                // The sign-in fails; only what was logged on the way matters
            }

            assertTrue(log.getMessages().stream().anyMatch(message -> message.contains("admin")));
            for (String message : log.getMessages())
                assertFalse(message, message.contains(KEY));
        }
    }
}

// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.web.data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What System > General > Environment shows of an environment variable or a
 * Java system property. Healdan's container keeps the database password in
 * its environment, and a variable or option can hold other passwords, keys
 * and tokens, so the tab masks a value whose name looks secret, a secret
 * {@code -D} option inside a value such as {@code CATALINA_OPTS}, and the
 * password in an address. It shows the rest, and that a secret is empty.
 */
public final class HealdanSecrets {

    /** What the tab shows in place of a secret */
    public static final String MASK = "********";

    // A name with one of these in it, in any case, holds a secret
    private static final String SECRET_WORDS = "password|passwd|secret|token|credential|api[._-]?key|access[._-]?key";

    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(" + SECRET_WORDS + ").*");

    // -Dname=value, quoted or not, with a secret name
    private static final Pattern SECRET_OPTION = Pattern
            .compile("(?i)(-D[^\\s=]*(?:" + SECRET_WORDS + ")[^\\s=]*=)(\"[^\"]*\"|'[^']*'|\\S*)");

    // scheme://user:password@, as in a proxy's address
    private static final Pattern ADDRESS_PASSWORD = Pattern.compile("(://[^/\\s:@]*:)[^/\\s@]*(@)");

    // password=... in an address's query
    private static final Pattern QUERY_PASSWORD = Pattern.compile("(?i)([?&;](?:" + SECRET_WORDS + ")=)[^&;\\s]*");

    private HealdanSecrets() {
    }

    /**
     * @param name the variable's or the property's name
     * @param value its value
     *
     * @return what the Environment tab shows of it
     */
    public static String shown(String name, String value) {
        if (value == null || value.isEmpty())
            return value;
        if (name != null && SECRET_NAME.matcher(name).matches())
            return MASK;

        String shown = SECRET_OPTION.matcher(value).replaceAll("$1" + Matcher.quoteReplacement(MASK));
        shown = ADDRESS_PASSWORD.matcher(shown).replaceAll("$1" + Matcher.quoteReplacement(MASK) + "$2");
        return QUERY_PASSWORD.matcher(shown).replaceAll("$1" + Matcher.quoteReplacement(MASK));
    }
}

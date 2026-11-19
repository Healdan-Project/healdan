// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * The WAR's classes and libraries hold one hibernate.properties, healdan-core's, which binds every Java boolean as an
 * integer, as LogicalDOC's int columns need on PostgreSQL (spec section 7.1). Hibernate reads only the first one on the
 * classpath, so a jar that one day brings its own would silently drop the setting, depending on the order the server
 * lists its jars in, and one in WEB-INF/classes, which the server searches first, always would; this fails the image
 * build instead. The class path build.sh runs it on has WEB-INF/classes ahead of WEB-INF/lib.
 * Usage: WarHibernateTest
 */
public class WarHibernateTest {

    public static void main(String[] args) throws Exception {
        List<URL> found = Collections.list(WarHibernateTest.class.getClassLoader().getResources("hibernate.properties"));
        Check.eq(1, found.size(), "hibernate.properties in the WAR " + found);
        if (found.size() == 1) {
            Properties properties = new Properties();
            try (InputStream in = found.get(0).openStream()) {
                properties.load(in);
            }
            Check.eq("INTEGER", properties.getProperty("hibernate.type.preferred_boolean_jdbc_type"),
                    "hibernate.type.preferred_boolean_jdbc_type in " + found.get(0));
        }
        Check.done("WarHibernateTest");
    }
}

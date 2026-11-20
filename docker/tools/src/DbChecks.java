// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

/**
 * What the start-up tool asks of the PostgreSQL server and database it's given before it creates or changes anything
 * in them (spec sections 6.1 to 6.3): pure decisions over what the server reports, each giving the message to stop or
 * warn with, or null when there's nothing to say.
 */
final class DbChecks {

    /** The oldest major version Healdan runs on: an older server is refused */
    static final int OLDEST = 18;

    /** The major version Healdan is tested on, the one compose.yaml pins: a newer one is logged as untested */
    static final int TESTED = 18;

    /**
     * The app's role's lock_timeout in its database, as PostgreSQL shows it: MariaDB's default lock wait, so that a
     * wait nothing else ends, such as the cycle FileLocks describes, fails one of its statements (spec section 7.4)
     */
    static final String LOCK_TIMEOUT = "50s";

    /** A database as Healdan's own stack makes it, which a message that asks for another names */
    private static final String CREATE_DATABASE = "create database <name> owner <role> template template0"
            + " encoding 'UTF8' locale_provider builtin builtin_locale 'C.UTF-8' locale 'C'";

    private DbChecks() {
    }

    /** @return the refusal for a server too old, or null. versionNum is PostgreSQL's server_version_num, e.g. 180004 */
    static String tooOld(int versionNum) {
        if (versionNum / 10000 >= OLDEST)
            return null;
        return "the database server is PostgreSQL " + major(versionNum) + ", and Healdan needs PostgreSQL " + OLDEST
                + " or later: point DB_HOST at a newer server, or move the database to one with a dump and a restore";
    }

    /** @return the note for a major newer than TESTED, or null */
    static String untested(int versionNum) {
        if (versionNum / 10000 <= TESTED)
            return null;
        return "the database server is PostgreSQL " + major(versionNum) + ", which Healdan hasn't been tested on (it's"
                + " tested on PostgreSQL " + TESTED + ")";
    }

    /** @return the refusal for a database that isn't UTF8, or null */
    static String wrongEncoding(String encoding) {
        if ("UTF8".equals(encoding))
            return null;
        return "the database's encoding is " + encoding + ", and Healdan needs UTF8. A database's encoding can't be"
                + " changed: make a new one with " + CREATE_DATABASE + ", and point DB_NAME at it";
    }

    /**
     * Built-in collations all sort by code point, and libc's C and POSIX do. Any other, glibc's C.UTF-8 included, is
     * one PostgreSQL can't use an index for in a LIKE on a prefix, as LogicalDOC's folder lookups are (recon section
     * 2.3 and question Q5).
     *
     * @return the warning for a database that doesn't sort by code point, or null. provider is
     *         pg_database.datlocprovider ('b' built-in, 'c' libc, 'i' ICU), collate its datcollate, locale its
     *         datlocale (null for libc)
     */
    static String otherLocale(String provider, String collate, String locale) {
        boolean libc = "c".equals(provider);
        if ("b".equals(provider) || libc && ("C".equals(collate) || "POSIX".equals(collate)))
            return null;
        String name = libc ? collate + " (libc)" : "i".equals(provider) ? locale + " (ICU)" : locale;
        return "the database's collation is " + name + ", which PostgreSQL doesn't take as sorting by code point:"
                + " Healdan's lists can sort in another order, and LogicalDOC's folder lookups can't use their index."
                + " Healdan starts on it, but a database's locale can't be changed: to change it, dump the database and"
                + " restore it into one made with " + CREATE_DATABASE;
    }

    /** @return the refusal for a role that can't create tables in the schema, or null */
    static String cannotCreate(boolean hasCreate, String role, String schema) {
        if (hasCreate)
            return null;
        if (schema == null)
            return "the role '" + role + "' has no schema to create tables in: no schema its search_path names exists";
        return "the role '" + role + "' can't create tables in the schema '" + schema + "', as Healdan has to: make it"
                + " the database's owner (alter database <name> owner to " + role + "), or grant create on schema "
                + schema + " to " + role;
    }

    /** The major version, as PostgreSQL names it: 18 from 180004, and 9.6 from 90624 */
    private static String major(int versionNum) {
        return versionNum >= 100000 ? String.valueOf(versionNum / 10000)
                : versionNum / 10000 + "." + versionNum / 100 % 100;
    }
}

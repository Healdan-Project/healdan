#!/bin/bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only
#
# Runs once, when the postgres image initialises an empty data folder (the image runs an executable script here and
# sources one that isn't, so this works either way). It makes the ordinary role the app connects as, its database,
# and takes the superuser's password away. The socket inside the container stays trusted (that's where backups and
# an admin's psql run), and initdb's --auth-host (compose.yaml) makes every TCP connection, the container's own
# loopback included, ask for a password; so the superuser, with none, is reachable only from inside the container.
# Every value reaches SQL through psql's -v and :"name" / :'name', never pasted into the text.
# The image stops on this script's exit status alone, so every failure here is an explicit exit 1.

superuser="${POSTGRES_USER:-postgres}"

# initdb's --auth-host should have left no TCP rule that trusts anyone. If a later image writes the file another
# way, each such rule is made to ask for a password, before anything else here can stop: exiting wouldn't do, as the
# image never runs these scripts again on a data folder it has made, and the restart would start the server on the
# file as it is. Every TCP form counts: host, hostssl, hostnossl, hostgssenc and hostnogssenc, with any indent, whose
# method, the field after the database, the user, the address and the address's mask when it has one, is trust.
trusting='^([[:space:]]*host(ssl|nossl|gssenc|nogssenc)?[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+([^[:space:]]+[[:space:]]+)?)trust([[:space:]]|$)'
if grep -Eq "$trusting" "$PGDATA/pg_hba.conf"; then
	echo "pg_hba.conf trusts a TCP connection: each such rule is made to ask for a password (scram-sha-256)." >&2
	sed -E -i "s/$trusting/\\1scram-sha-256\\4/" "$PGDATA/pg_hba.conf" || exit 1
fi
if grep -Eq "$trusting" "$PGDATA/pg_hba.conf"; then
	echo "pg_hba.conf still trusts a TCP connection; the superuser would be reachable without a password." >&2
	exit 1
fi

if [ -z "${HEALDAN_DB_USER:-}" ] || [ -z "${HEALDAN_DB_NAME:-}" ] || [ -z "${HEALDAN_DB_PASSWORD:-}" ]; then
	echo "DB_NAME, DB_USER and DB_PASSWORD must all be set, and not empty." >&2
	exit 1
fi

if [ "$HEALDAN_DB_USER" = "$superuser" ]; then
	echo "DB_USER must not be '$superuser', the database server's own superuser: the app connects as an ordinary role. Choose another DB_USER." >&2
	exit 1
fi

case "$HEALDAN_DB_USER" in
	pg_*)
		echo "DB_USER must not begin with 'pg_': PostgreSQL reserves those names. Choose another DB_USER." >&2
		exit 1
		;;
esac

case "$HEALDAN_DB_NAME" in
	postgres|template0|template1)
		echo "DB_NAME must not be '$HEALDAN_DB_NAME', a database the server already has. Choose another DB_NAME." >&2
		exit 1
		;;
esac

# log_min_error_statement = panic first, so a failing statement is never written to the server's log: the first
# one holds the password.
psql -v ON_ERROR_STOP=1 -U "$superuser" -d "${POSTGRES_DB:-postgres}" \
	-v u="$HEALDAN_DB_USER" -v d="$HEALDAN_DB_NAME" -v p="$HEALDAN_DB_PASSWORD" -v admin="$superuser" <<'SQL' || exit 1
set log_min_error_statement = panic;
create role :"u" login password :'p';
create database :"d" owner :"u" template template0 encoding 'UTF8' locale_provider builtin builtin_locale 'C.UTF-8' locale 'C';
alter role :"admin" password null;
SQL

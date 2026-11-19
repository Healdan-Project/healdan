#!/bin/bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only
#
# Runs once, when the postgres image initialises an empty data folder (the image runs an executable script here and
# sources one that isn't, so this works either way). It makes the ordinary role the app connects as, its database,
# and takes the superuser's password away, so the superuser is reachable only from inside this container.
# Every value reaches SQL through psql's -v and :"name" / :'name', never pasted into the text.

superuser="${POSTGRES_USER:-postgres}"

if [ -z "${HEALDAN_DB_USER:-}" ] || [ -z "${HEALDAN_DB_NAME:-}" ] || [ -z "${HEALDAN_DB_PASSWORD:-}" ]; then
	echo "HEALDAN_DB_NAME, HEALDAN_DB_USER and HEALDAN_DB_PASSWORD must all be set." >&2
	exit 1
fi

if [ "$HEALDAN_DB_USER" = "$superuser" ]; then
	echo "DB_USER must not be '$superuser', the database server's own superuser: the app connects as an ordinary role. Choose another DB_USER." >&2
	exit 1
fi

psql -v ON_ERROR_STOP=1 -U "$superuser" -d "${POSTGRES_DB:-postgres}" \
	-v u="$HEALDAN_DB_USER" -v d="$HEALDAN_DB_NAME" -v p="$HEALDAN_DB_PASSWORD" -v admin="$superuser" <<'SQL'
create role :"u" login password :'p';
create database :"d" owner :"u" template template0 encoding 'UTF8' locale_provider builtin builtin_locale 'C.UTF-8' locale 'C';
alter role :"admin" password null;
SQL

# The image trusts connections from its own loopback addresses, which would let anyone in the container (or the
# host, through a published port reaching the loopback rule) sign in as the superuser without a password. Make them
# ask for one, as every other address does; the local socket, which only a person inside the container can reach,
# stays trusted. The image stops this temporary server and starts the real one after the scripts, which reads this.
sed -i -E 's/^(host[[:space:]].*[[:space:]])trust$/\1scram-sha-256/' "$PGDATA/pg_hba.conf"

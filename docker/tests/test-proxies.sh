#!/usr/bin/env bash
# Which forwarded addresses Tomcat believes (server.xml's RemoteIpValve, fed by DockerInit's TrustedProxies). With
# LOGICALDOC_TRUSTED_PROXIES unset, only the Docker networks the app is on are trusted: the Docker host's own
# requests, which arrive from the network's gateway, but not a private address in the chain or a request from inside
# the container. The sign-in throttle's per-address counters (ld_sequence) show which address the app saw.
# Usage: BASE=http://127.0.0.1:8080 APP_CONTAINER=<id> DB_CONTAINER=<id> docker/tests/test-proxies.sh
#        (needs curl and docker; the stack must run with LOGICALDOC_TRUSTED_PROXIES unset)
# docker/smoke-test.sh runs it as step 2b.
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

: "${BASE:?}" "${APP_CONTAINER:?}" "${DB_CONTAINER:?}"

db_query() {
  docker exec "$DB_CONTAINER" sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -Nse "$1"' \
    sh "$1"
}
# fail_signin <base> <X-Forwarded-For> [docker exec prefix...]: one wrong password for an unknown user; prints the status.
fail_signin() {
  local base=$1 forwarded=$2
  shift 2
  "$@" curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -H "X-Forwarded-For: $forwarded" \
    -d '{"username": "proxycheck", "password": "wrong-password"}' "$base/api/v1/auth/tokens"
}
counted() { grep -qx "loginfail-ip-$1" <<<"$counters"; }
not_counted() { ! counted "$1"; }

check "from the Docker host: refused" test "$(fail_signin "$BASE" 203.0.113.7)" = 401
# What nginx and NPM send when a LAN client at 10.99.0.5 made up the first address: the client's real address last.
check "through a proxy on the Docker host: refused" test "$(fail_signin "$BASE" '203.0.113.11, 10.99.0.5')" = 401
check "from inside the app container: refused" \
  test "$(fail_signin http://127.0.0.1:8080 203.0.113.8 docker exec "$APP_CONTAINER")" = 401

counters=$(db_query "select ld_name from ld_sequence where ld_name like 'loginfail-ip-%'")
check "the Docker host's forwarded address is believed" counted 203.0.113.7
check "a private address in the chain is the client" counted 10.99.0.5
check "an address the client made up is not believed" not_counted 203.0.113.11
check "a request from inside the container counts as 127.0.0.1" counted 127.0.0.1
check "its forwarded address is ignored" not_counted 203.0.113.8

finish

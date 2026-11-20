#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# The User app in Chromium and WebKit against the smoke stack (M6 spec §15.1): the specs in
# healdan-web/web/e2e/ run in Playwright's Docker image, whose tag is the version of @playwright/test in the
# workspace's package-lock.json. Then curl checks the forgery refusals a browser can't make: a cross-site sign-out
# and a sign-out without the app's header, and that the web session's GET works.
# Needs the smoke fixture, whose SmokeWebUserSeed makes webuser (test-api.sh's apiuser is blocked by its throttle
# check, so the browser tests use another user), a running stack, Docker, curl and python3.
# Usage: BASE=http://127.0.0.1:18080 docker/tests/test-web.sh
# The browser container shares the host's network (--network host), which Linux and CI allow. Docker Desktop on a Mac
# doesn't reach the host's ports that way: there, set WEB_NETWORK=bridge. The browser container then joins the network
# of the container that publishes BASE's port, and still opens http://127.0.0.1:<port>, which a small proxy in its
# container forwards to that container's own address and port, because the web session refuses a request whose Origin
# isn't the server's own address (a different name for it fails every spec).
# The proxy doesn't go through the host (host.docker.internal:<port>), as it did until 2026-11-19. When the server is
# the first to close a connection made that way, as Tomcat is after a request with Connection: close (every call of
# helpers.ts' apiContext), Docker Desktop's network remembers its source port for 60 seconds and answers no new
# connection from it. Linux picks its source ports afresh every 10 seconds, so one comes round again within the minute,
# and the connection opens only when the kernel tries again after that: 11, 19, 35 or 67 seconds late, about one
# connection in 700. WebKit sends every POST and PATCH on a new connection, so a save waited and its test failed at 30
# seconds, a different one each run; Chromium sends them on connections it has, and never failed.
# finding.spec searches for documents it has just filed, which the search index only has once the Indexer has run
# (every 30 minutes). docker/smoke-test.sh sets it to every 5 seconds before running this; for a run by hand, do the
# same (Developer Guide, Tests) and set HEALDAN_INDEXER_FAST=1, or finding.spec waits 80 s and fails.
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

BASE=${BASE:-http://127.0.0.1:18080}
WEB_NETWORK=${WEB_NETWORK:-host}
port=${BASE##*:}
if [ "$WEB_NETWORK" = host ]; then
  WEB_BROWSER_BASE=${WEB_BROWSER_BASE:-$BASE}
  PROXY=
else
  WEB_BROWSER_BASE=${WEB_BROWSER_BASE:-http://127.0.0.1:$port}
  # The container that publishes the port, the first network it is on, its address there and the port it listens on
  app=$(docker ps -q --filter "publish=$port" | head -n 1)
  if [ -z "$app" ]; then
    echo "WEB_NETWORK=$WEB_NETWORK needs the stack on this Docker: no running container publishes port $port"
    exit 1
  fi
  WEB_NETWORK=$(docker inspect -f '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' "$app" | head -n 1)
  app_address=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$WEB_NETWORK\").IPAddress}}" "$app")
  app_port=$(docker port "$app" | sed -n "s|^\([0-9]*\)/tcp -> .*:$port\$|\1|p" | head -n 1)
  if [ -z "$app_address" ] || [ -z "$app_port" ]; then
    echo "Couldn't find the address and port of the container that publishes port $port (container $app, network '$WEB_NETWORK')"
    exit 1
  fi
  PROXY="node -e \"require('net').createServer(c=>{const u=require('net').connect($app_port,'$app_address');c.pipe(u);u.pipe(c);c.on('error',()=>u.destroy());u.on('error',()=>c.destroy())}).listen($port,'127.0.0.1')\" &"
fi
[ -n "${HEALDAN_INDEXER_FAST:-}" ] || echo "WARNING: HEALDAN_INDEXER_FAST isn't set: unless the Indexer runs every few seconds, finding.spec's searches fail"
# A throwaway password for a throwaway user on a throwaway stack: healdan-core's SmokeWebUserSeed.PASSWORD.
WEBUSER_PASSWORD='Web-Smoke-Pw-42!x'
export WEBUSER_PASSWORD
# The smoke stack's own administrator, whose published password the smoke stack itself uses (docker/smoke-test.sh): for what only
# an administrator sees, such as trash.spec's check of the Trash's Removed view.
ADMIN_PASSWORD=admin
export ADMIN_PASSWORD

lock=healdan-web/web/package-lock.json
version=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['packages']['node_modules/@playwright/test']['version'])" "$lock")

browsers() {
  # The workspace is copied in, without node_modules, so npm ci's Linux packages never replace a host's own.
  docker run --rm --network "$WEB_NETWORK" --ipc=host -e BASE="$WEB_BROWSER_BASE" -e WEBUSER_PASSWORD -e ADMIN_PASSWORD \
    -v "$PWD/healdan-web/web:/src:ro" "mcr.microsoft.com/playwright:v${version}-noble" \
    bash -c "$PROXY "'mkdir /work && cd /src && tar cf - --exclude=node_modules --exclude=dist --exclude=e2e-results . | tar xf - -C /work \
      && cd /work && npm ci --ignore-scripts --no-audit --no-fund && { npx playwright test --reporter=line || { status=$?
        # The container goes with its results: the page of each failed test, as Playwright saw it, says what was on screen
        for f in e2e-results/*/error-context.md; do [ -f "$f" ] && { echo "== $f"; head -c 8000 "$f"; echo; }; done
        exit $status; }; }'
}
check "every spec in healdan-web/web/e2e/, in Chromium and WebKit" browsers

COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT
# Every call has a deadline, so a stack that stopped answering fails here rather than holding the job
code() { curl -s --max-time 30 -b "$COOKIES" -o /dev/null -w '%{http_code}' "$@"; }

# The sign-in page's form, as a browser posts it. LogicalDOC answers one that works with the session's id in a SID header,
# and one that fails with a redirect to the context's root
signs_in() {
  local headers
  curl -s --max-time 30 -c "$COOKIES" -o /dev/null "$BASE/login.jsp" || return 1
  headers=$(curl -s --max-time 30 -b "$COOKIES" -c "$COOKIES" -o /dev/null -D - \
    -d 'j_username=webuser' --data-urlencode "j_password=$WEBUSER_PASSWORD" "$BASE/j_spring_security_check") || return 1
  grep -qi '^SID: ' <<<"$headers"
}
check "curl signs in as webuser" signs_in
check "the web session's GET answers 200" test "$(code -H 'Sec-Fetch-Site: same-origin' "$BASE/api/v1/me")" = 200
check "a cross-site sign-out is refused with 401" \
  test "$(code -H 'Sec-Fetch-Site: cross-site' -H 'X-Healdan-Client: web' -X DELETE "$BASE/api/v1/session")" = 401
check "a sign-out without the app's header is refused with 403" \
  test "$(code -H 'Sec-Fetch-Site: same-origin' -X DELETE "$BASE/api/v1/session")" = 403
check "the session is still there after the refusals" test "$(code -H 'Sec-Fetch-Site: same-origin' "$BASE/api/v1/me")" = 200

finish

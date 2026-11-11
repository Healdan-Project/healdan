#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# The User app in Chromium and WebKit against the smoke stack (M6 spec §15.1): the specs in
# healdan-web/web/e2e/ run in Playwright's Docker image, whose tag is the version of @playwright/test in the
# workspace's package-lock.json. Then curl checks the forgery refusals a browser can't make: a cross-site sign-out
# and a sign-out without the app's header, and that the web session's GET works.
# Needs the smoke fixture, whose SmokeWebUserSeed makes webuser (test-api.sh's apiuser is blocked by its throttle
# check, so the browser tests use another user), a running stack, Docker, curl, and node or python3.
# Usage: BASE=http://127.0.0.1:18080 docker/tests/test-web.sh
# The browser container shares the host's network (--network host), which Linux and CI allow. Docker Desktop on a Mac
# doesn't reach the host's ports that way: there, set WEB_NETWORK=bridge. The browser then still opens
# http://127.0.0.1:<port>, which a small proxy in its container forwards to host.docker.internal:<port>, because the web
# session refuses a request whose Origin isn't the server's own address (a different name for it fails every spec).
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
  PROXY="node -e \"require('net').createServer(c=>{const u=require('net').connect($port,'host.docker.internal');c.pipe(u);u.pipe(c);c.on('error',()=>u.destroy());u.on('error',()=>c.destroy())}).listen($port,'127.0.0.1')\" &"
fi
[ -n "${HEALDAN_INDEXER_FAST:-}" ] || echo "WARNING: HEALDAN_INDEXER_FAST isn't set: unless the Indexer runs every few seconds, finding.spec's searches fail"
# A throwaway password for a throwaway user on a throwaway stack: healdan-core's SmokeWebUserSeed.PASSWORD.
WEBUSER_PASSWORD='Web-Smoke-Pw-42!x'
export WEBUSER_PASSWORD

lock=healdan-web/web/package-lock.json
version=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['packages']['node_modules/@playwright/test']['version'])" "$lock")

browsers() {
  # The workspace is copied in, without node_modules, so npm ci's Linux packages never replace a host's own.
  docker run --rm --network "$WEB_NETWORK" --ipc=host -e BASE="$WEB_BROWSER_BASE" -e WEBUSER_PASSWORD \
    -v "$PWD/healdan-web/web:/src:ro" "mcr.microsoft.com/playwright:v${version}-noble" \
    bash -c "$PROXY "'mkdir /work && cd /src && tar cf - --exclude=node_modules --exclude=dist --exclude=e2e-results . | tar xf - -C /work \
      && cd /work && npm ci --ignore-scripts --no-audit --no-fund && npx playwright test --reporter=line'
}
check "the sign-in and the way across, in Chromium and WebKit" browsers

COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT
code() { curl -s -b "$COOKIES" -o /dev/null -w '%{http_code}' "$@"; }

curl -s -c "$COOKIES" -o /dev/null "$BASE/login.jsp"
curl -s -b "$COOKIES" -c "$COOKIES" -o /dev/null -d 'j_username=webuser' --data-urlencode "j_password=$WEBUSER_PASSWORD" \
  "$BASE/j_spring_security_check"
check "the web session's GET answers 200" test "$(code -H 'Sec-Fetch-Site: same-origin' "$BASE/api/v1/me")" = 200
check "a cross-site sign-out is refused with 401" \
  test "$(code -H 'Sec-Fetch-Site: cross-site' -H 'X-Healdan-Client: web' -X DELETE "$BASE/api/v1/session")" = 401
check "a sign-out without the app's header is refused with 401" \
  test "$(code -H 'Sec-Fetch-Site: same-origin' -X DELETE "$BASE/api/v1/session")" = 401
check "the session is still there after the refusals" test "$(code -H 'Sec-Fetch-Site: same-origin' "$BASE/api/v1/me")" = 200

finish

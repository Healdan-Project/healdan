#!/usr/bin/env bash
# Checks compose.dev.yaml and compose.proxy.yaml: the rendered config, then a live stack with each.
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

PROJECT=logicaldoc-overlays
NET=ldtest-proxy
WORK=$(mktemp -d)
ENV_FILE=$WORK/test.env
printf "DB_PASSWORD='overlay test'\nPROXY_NETWORK=%s\nLOGICALDOC_PORT=18081\nDEV_DB_PORT=13306\n" "$NET" > "$ENV_FILE"

compose() { docker compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"; }
remove_stack() {
  compose -f compose.yaml down -v --remove-orphans >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
cleanup() { remove_stack; rm -rf "$WORK"; }
trap cleanup EXIT
remove_stack
docker network create "$NET" >/dev/null

echo "== rendered config: dev overlay"
dev=$(compose -f compose.yaml -f compose.dev.yaml config --format json)
check "app ports are LAN UI + local debugger" test \
  "$(jq -c '[.services.app.ports[] | "\(.host_ip):\(.published):\(.target)"] | sort' <<<"$dev")" = \
  '["0.0.0.0:18081:8080","127.0.0.1:5005:5005"]'
check "Tomcat started with jpda" test "$(jq -c .services.app.command <<<"$dev")" = '["catalina.sh","jpda","run"]'
check "JPDA_ADDRESS set" test "$(jq -r .services.app.environment.JPDA_ADDRESS <<<"$dev")" = '*:5005'
check "db published on localhost" test \
  "$(jq -c '[.services.db.ports[] | "\(.host_ip):\(.published):\(.target)"]' <<<"$dev")" = '["127.0.0.1:13306:3306"]'

echo "== rendered config: proxy overlay"
proxy=$(compose -f compose.yaml -f compose.proxy.yaml config --format json)
check "no host ports" test "$(jq '.services.app.ports // [] | length' <<<"$proxy")" = 0
check "alias logicaldoc on the proxy network" test "$(jq -c '.services.app.networks.proxy.aliases' <<<"$proxy")" = '["logicaldoc"]'
check "proxy network is external and named from PROXY_NETWORK" test \
  "$(jq -c '[.networks.proxy.name, .networks.proxy.external]' <<<"$proxy")" = "[\"$NET\",true]"

echo "== live: proxy overlay"
compose -f compose.yaml -f compose.proxy.yaml up -d --build
check "app healthy" wait_healthy "$(compose -f compose.yaml ps -q app)" 900
check "reachable as logicaldoc:8080 from the proxy network" test \
  "$(docker run --rm --network "$NET" curlimages/curl:8.11.1 -s -o /dev/null -w '%{http_code}' http://logicaldoc:8080/login.jsp)" = 200
check "nothing published on the host" test "$(http_code http://127.0.0.1:18081/login.jsp || true)" = 000

echo "== live: dev overlay"
compose -f compose.yaml -f compose.dev.yaml up -d
check "app healthy" wait_healthy "$(compose -f compose.yaml ps -q app)" 900
check "UI on the published port" test "$(http_code http://127.0.0.1:18081/login.jsp)" = 200
check "JDWP handshake on 5005" python3 - <<'PY'
import socket, sys
s = socket.create_connection(("127.0.0.1", 5005), timeout=10)
s.sendall(b"JDWP-Handshake")
sys.exit(0 if s.recv(14) == b"JDWP-Handshake" else 1)
PY
check "MariaDB on 127.0.0.1:13306" python3 -c 'import socket; socket.create_connection(("127.0.0.1", 13306), timeout=5)'

finish

#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Healdan Project
# SPDX-License-Identifier: LGPL-3.0-only

# The PostgreSQL Healdan's Java tests run on: postgres:18 in the container healdan-test-db, on the Docker network
# healdan-test, published on 127.0.0.1 only. Each test makes a database of its own on it (TestDatabase), with the
# encoding and locale a real install has, so the server's own default doesn't matter; initdb gets them anyway. Its data
# lives on a tmpfs, and it runs without fsync, as nothing on it needs to outlive the container. Its password is hashed
# with one SCRAM iteration, not PostgreSQL's 4,096: every test opens some 40 connections (LogicalDOC reads each setting
# of its context on a connection of its own), and the hashing was most of what a connection cost.
# Usage: docker/tests/test-db.sh start|stop
#        HEALDAN_TEST_DB_PORT publishes another host port than 5432
set -euo pipefail

NAME=healdan-test-db
NETWORK=healdan-test
PORT=${HEALDAN_TEST_DB_PORT:-5432}

usage() {
  echo "Usage: $0 start|stop   (HEALDAN_TEST_DB_PORT sets the host port, 5432 by default)" >&2
  exit 2
}

# Prints "running", "stopped" (it exists, not running) or "missing".
state() {
  local running
  if ! running=$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null); then
    echo missing
  elif [ "$running" = true ]; then
    echo running
  else
    echo stopped
  fi
}

how_to_reach() {
  echo "From the host: HEALDAN_TEST_DB_URL=jdbc:postgresql://localhost:$PORT/"
  echo "From a Maven container started with --network $NETWORK: HEALDAN_TEST_DB_URL=jdbc:postgresql://$NAME:5432/"
}

start() {
  case $(state) in
    running)
      echo "$NAME is already running."
      # The host port it publishes, which may not be the one HEALDAN_TEST_DB_PORT names now
      PORT=$(docker port "$NAME" 5432/tcp | head -n 1)
      PORT=${PORT##*:}
      how_to_reach
      return 0
      ;;
    # Its data was on a tmpfs, so there's nothing in a stopped one to keep
    stopped) docker rm -f "$NAME" >/dev/null ;;
  esac
  docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null

  # The image's volume is /var/lib/postgresql, which holds PGDATA. scram_iterations goes to initdb, which hashes the
  # user's password: as a server setting it would come too late
  local initdb="--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8 --locale=C -c scram_iterations=1"
  docker run -d --name "$NAME" --network "$NETWORK" -p "127.0.0.1:$PORT:5432" \
    --tmpfs /var/lib/postgresql \
    -e POSTGRES_USER=healdan -e POSTGRES_PASSWORD=healdan-test \
    -e POSTGRES_INITDB_ARGS="$initdb" \
    postgres:18 \
    -c fsync=off -c synchronous_commit=off -c full_page_writes=off -c max_connections=300 >/dev/null

  # Over TCP: the entrypoint's first server, which runs the initialisation, listens only on its socket
  local deadline=$((SECONDS + 60))
  until docker exec "$NAME" pg_isready -q -h 127.0.0.1 -U healdan >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ] || [ "$(state)" != running ]; then
      echo "$NAME didn't become ready. Its last lines:" >&2
      docker logs --tail 30 "$NAME" >&2 || true
      exit 1
    fi
    sleep 1
  done
  echo "$NAME is ready."
  how_to_reach
}

stop() {
  if [ "$(state)" = missing ]; then
    echo "$NAME isn't there."
  else
    docker rm -f "$NAME" >/dev/null
    echo "$NAME removed."
  fi
}

[ $# -eq 1 ] || usage
case $1 in
  start) start ;;
  stop) stop ;;
  *) usage ;;
esac

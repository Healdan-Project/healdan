#!/bin/sh
# Run by Admin > Restart (LDOCHOME/bin/restart.sh). Tomcat is PID 1, so stopping it stops the container and
# the restart policy (restart: unless-stopped) starts it again.
kill -TERM 1

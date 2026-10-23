#!/bin/sh
# Run by Admin > Restart (LDOCHOME/bin/restart.sh). PID 1 is tini, which forwards the signal to Tomcat; Tomcat
# stopping stops the container, and the restart policy (restart: unless-stopped) starts it again.
kill -TERM 1

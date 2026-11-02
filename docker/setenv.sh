#!/bin/sh
# Sourced by catalina.sh. CATALINA_OPTS from the environment (heap size and so on) is kept and extended.
# healdan.trustedProxies: the addresses server.xml's RemoteIpValve believes forwarded headers from, as DockerInit
# worked them out on this start. Without the file, none.
# healdan.secretKey: the file of the key that seals the secrets provider sign-in keeps (SecretBox), in the
# configuration volume. Healdan makes it the first time it seals one; it must be backed up with the database.
CATALINA_OPTS="${CATALINA_OPTS:-} -Dfile.encoding=UTF-8 -Dhealdan.trustedProxies=$(cat /opt/logicaldoc/run/trusted-proxies 2>/dev/null) -Dhealdan.secretKey=/opt/logicaldoc/conf/healdan-secret.key"

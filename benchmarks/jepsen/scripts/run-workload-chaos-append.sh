#!/bin/bash
set +e
export PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export JAVA_HOME=/opt/java/openjdk
export JAVA_CMD=/opt/java/openjdk/bin/java
export JVM_OPTS="-Xmx2g -XX:+UseG1GC"
export LEIN_JVM_OPTS="-Xmx1g"
export JAVA_TOOL_OPTIONS="--enable-preview -Xmx2g"
cd /jepsen/jamoa
export JEPSEN_NODES=n1,n2,n3 JEPSEN_HTTP_PORTS=7777,7778,7779 JEPSEN_SQL_PORTS=15432,15433,15434
export JEPSEN_SCRIPTS=/jepsen/scripts JEPSEN_USE_LOCALHOST=0
export JEPSEN_UNCLEAN_REVIVE=1
export JEPSEN_UNCLEAN_DOWN_SEC=15
command -v lein
command -v git
# Never co-run workloads: leftover lein/clojure from a prior hang races store symlinks + Knossos.
pkill -9 -f 'jamoa-jepsen.core' 2>/dev/null || true
pkill -9 -f 'lein run -m jamoa-jepsen' 2>/dev/null || true
sleep 1
lein run -m jamoa-jepsen.core test --workload append --time-limit 60
echo LEIN_EXIT=$?
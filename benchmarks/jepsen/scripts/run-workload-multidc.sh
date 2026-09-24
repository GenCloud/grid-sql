#!/bin/bash
set +e
export PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export JAVA_HOME=/opt/java/openjdk
export JAVA_CMD=/opt/java/openjdk/bin/java
export JVM_OPTS="-Xmx12g -XX:+UseG1GC"
export LEIN_JVM_OPTS="-Xmx2g"
export JAVA_TOOL_OPTIONS="--enable-preview -Xmx12g"
cd /jepsen/jamoa
export JEPSEN_NODES=a1,a2,a3,b1,b2
export JEPSEN_HTTP_PORTS=7777,7778,7779,7780,7781
export JEPSEN_SQL_PORTS=15432,15433,15434,15435,15436
export JEPSEN_SCRIPTS=/jepsen/scripts
export JEPSEN_USE_LOCALHOST=0
export JEPSEN_MULTIDC=1
export JEPSEN_MULTI_HOST=1
# Propagate ASYNC vs SYNC so nemesis-dc-link.sh kill-voter targets the right node.
export MULTIDC_MODE="${MULTIDC_MODE:-async}"
export JEPSEN_GRID_URL="grid://@a1:15432,a2:15433,a3:15434,b1:15435/public?maxConnections=1&maxTxContexts=64"
if [[ "${JEPSEN_WITNESS:-}" == "1" ]]; then
  export JEPSEN_NODES=a1,a2,a3,b1,b2,w1
  export JEPSEN_HTTP_PORTS=7777,7778,7779,7780,7781,7782
  export JEPSEN_SQL_PORTS=15432,15433,15434,15435,15436,15437
  export JEPSEN_GRID_URL="grid://@a1:15432,a2:15433,a3:15434,b1:15435,w1:15437/public?maxConnections=1&maxTxContexts=64"
fi
WL="${1:-register}"
TL="${2:-60}"
NEM="${3:-}"
command -v lein
command -v docker || true
if [[ -n "$NEM" ]]; then
  lein run -m jamoa-jepsen.core test --workload "$WL" --time-limit "$TL" --no-nemesis
else
  lein run -m jamoa-jepsen.core test --workload "$WL" --time-limit "$TL"
fi
echo LEIN_EXIT=$?
#!/usr/bin/env bash
# Compute per-instance compose / host-port / container-prefix env for parallel Jepsen cells.
# Source:  . benchmarks/jepsen/scripts/jepsen-instance-env.sh
# Inputs:
#   JEPSEN_INSTANCE   short id (e.g. A, I, p3) — empty = legacy singleton names/ports
#   JEPSEN_PORT_OFFSET integer host-port offset (default 0; parallel cells use 0,100,200,...)
set -euo pipefail

JEPSEN_INSTANCE="${JEPSEN_INSTANCE:-}"
JEPSEN_PORT_OFFSET="${JEPSEN_PORT_OFFSET:-0}"

if [[ -n "$JEPSEN_INSTANCE" ]]; then
  _suf="-${JEPSEN_INSTANCE}"
  export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-jepsen${_suf}}"
else
  _suf=""
fi

export JEPSEN_1DC_CTR_PREFIX="${JEPSEN_1DC_CTR_PREFIX:-jamoa-jepsen${_suf}}"
export JEPSEN_MDC_CTR_PREFIX="${JEPSEN_MDC_CTR_PREFIX:-jamoa-multidc${_suf}}"
export JEPSEN_1DC_NET="${JEPSEN_1DC_NET:-jamoa-jepsen-net${_suf}}"
export JEPSEN_MDC_NET_A="${JEPSEN_MDC_NET_A:-jamoa-multidc-dc-a${_suf}}"
export JEPSEN_MDC_NET_B="${JEPSEN_MDC_NET_B:-jamoa-multidc-dc-b${_suf}}"
export JEPSEN_MDC_NET_LINK="${JEPSEN_MDC_NET_LINK:-jamoa-multidc-dc-link${_suf}}"

_off() { echo $(( $1 + JEPSEN_PORT_OFFSET )); }

export JEPSEN_HOST_HTTP_N1="$(_off 7777)"
export JEPSEN_HOST_HTTP_N2="$(_off 7778)"
export JEPSEN_HOST_HTTP_N3="$(_off 7779)"
export JEPSEN_HOST_HTTP_B1="$(_off 7780)"
export JEPSEN_HOST_HTTP_B2="$(_off 7781)"
export JEPSEN_HOST_HTTP_W1="$(_off 7782)"

export JEPSEN_HOST_SQL_N1="$(_off 15432)"
export JEPSEN_HOST_SQL_N2="$(_off 15433)"
export JEPSEN_HOST_SQL_N3="$(_off 15434)"
export JEPSEN_HOST_SQL_B1="$(_off 15435)"
export JEPSEN_HOST_SQL_B2="$(_off 15436)"
export JEPSEN_HOST_SQL_W1="$(_off 15437)"

export JEPSEN_HOST_REPL_N1="$(_off 5615)"
export JEPSEN_HOST_REPL_N2="$(_off 5616)"
export JEPSEN_HOST_REPL_N3="$(_off 5617)"
export JEPSEN_HOST_REPL_B1="$(_off 5618)"
export JEPSEN_HOST_REPL_B2="$(_off 5619)"
export JEPSEN_HOST_REPL_W1="$(_off 5620)"

export JEPSEN_HTTP_PORTS_1DC="${JEPSEN_HOST_HTTP_N1},${JEPSEN_HOST_HTTP_N2},${JEPSEN_HOST_HTTP_N3}"
export JEPSEN_SQL_PORTS_1DC="${JEPSEN_HOST_SQL_N1},${JEPSEN_HOST_SQL_N2},${JEPSEN_HOST_SQL_N3}"
export JEPSEN_HTTP_PORTS_MDC="${JEPSEN_HOST_HTTP_N1},${JEPSEN_HOST_HTTP_N2},${JEPSEN_HOST_HTTP_N3},${JEPSEN_HOST_HTTP_B1},${JEPSEN_HOST_HTTP_B2}"
export JEPSEN_SQL_PORTS_MDC="${JEPSEN_HOST_SQL_N1},${JEPSEN_HOST_SQL_N2},${JEPSEN_HOST_SQL_N3},${JEPSEN_HOST_SQL_B1},${JEPSEN_HOST_SQL_B2}"
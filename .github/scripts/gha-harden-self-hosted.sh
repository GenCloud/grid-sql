#!/usr/bin/env bash
# Mask host identity and secret-like env values in GitHub Actions logs.
# No-op on GitHub-hosted runners.
set -euo pipefail

is_self=0
re="${RUNNER_ENVIRONMENT:-}"
rl="${RUNNER_LABELS:-}"
if [[ "$re" == "self-hosted" ]]; then
  is_self=1
fi
if [[ ",$rl," == *",self-hosted,"* ]]; then
  is_self=1
fi
if [[ "$is_self" != "1" ]]; then
  exit 0
fi

mask() {
  local v="${1:-}"
  if [[ -z "$v" || ${#v} -lt 4 ]]; then
    return 0
  fi
  case "$v" in
    true|false|TRUE|FALSE|Linux|Windows|macOS|ubuntu-latest|self-hosted) return 0 ;;
  esac
  echo "::add-mask::$v"
}

mask_path() {
  local p="${1:-}"
  [[ -z "$p" ]] && return 0
  mask "$p"
  local alt
  alt="${p//\\//}"
  mask "$alt"
  alt="${p//\//\\}"
  mask "$alt"
}

mask "${USER:-}"
mask "${USERNAME:-}"
mask "${LOGNAME:-}"
mask "${COMPUTERNAME:-}"
mask "${HOSTNAME:-}"
mask "${RUNNER_NAME:-}"
if command -v hostname >/dev/null 2>&1; then
  mask "$(hostname 2>/dev/null || true)"
  mask "$(hostname -s 2>/dev/null || true)"
fi

for k in HOME USERPROFILE LOCALAPPDATA APPDATA TMPDIR TMP TEMP \
         GITHUB_WORKSPACE RUNNER_TEMP RUNNER_TOOL_CACHE RUNNER_WORKSPACE \
         JAVA_HOME JAVA_HOME_25_X64 M2_HOME MAVEN_HOME JEPSEN_M2; do
  eval "val=\${$k:-}"
  mask_path "$val"
done

if [[ -n "${GITHUB_WORKSPACE:-}" ]]; then
  parent="$(cd "${GITHUB_WORKSPACE}/../../.." 2>/dev/null && pwd || true)"
  mask_path "$parent"
fi

while IFS='=' read -r name value; do
  [[ -z "$name" ]] && continue
  uname="$(printf '%s' "$name" | tr '[:lower:]' '[:upper:]')"
  case "$uname" in
    *SECRET*|*TOKEN*|*PASSWORD*|*PASSWD*|*APIKEY*|*API_KEY*|*CREDENTIAL*|*PRIVATE_KEY*|*ACCESS_KEY*|*CONNECTION_STRING*|*CONNSTR*|*AUTH_HEADER*)
      case "$uname" in
        GITHUB_TOKEN|ACTIONS_RUNTIME_TOKEN|ACTIONS_RESULTS_URL|GITHUB_ENV|GITHUB_OUTPUT|GITHUB_PATH|GITHUB_STATE|GITHUB_STEP_SUMMARY) continue ;;
      esac
      mask "$value"
      ;;
  esac
done < <(env)

if [[ -n "${GITHUB_ENV:-}" ]]; then
  existing="${JAVA_TOOL_OPTIONS:-}"
  if [[ "$existing" != *"-Duser.name=gha"* ]]; then
    echo "JAVA_TOOL_OPTIONS=${existing} -Duser.name=gha" >> "$GITHUB_ENV"
  fi
fi
exit 0
#!/usr/bin/env bash
# Resolve GitHub Actions runs-on JSON.
# Env: DISPATCH_LABELS, VAR_LABELS, PR_LABELS, GITHUB_EVENT_NAME, GITHUB_OUTPUT
# Outputs: runs_on (JSON array), max_parallel, self_hosted
#
# pull_request: GitHub PR label "self-hosted" routes to a self-hosted runner.
# Extra PR labels Windows / Linux / macOS / X64 / ARM64 are passed as runner labels.
set -euo pipefail

hosted_json='["ubuntu-latest"]'

trim() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

emit_hosted() {
  echo "runs_on=${hosted_json}" >> "$GITHUB_OUTPUT"
  echo "max_parallel=4" >> "$GITHUB_OUTPUT"
  echo "self_hosted=false" >> "$GITHUB_OUTPUT"
  echo "Runner: ubuntu-latest (GitHub-hosted)"
}

emit_from_csv() {
  local raw
  raw="$(trim "$1")"
  raw="${raw//;/,}"
  local lower
  lower="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  if [[ -z "$raw" || "$lower" == "github" || "$lower" == "ubuntu-latest" || "$lower" == "hosted" ]]; then
    emit_hosted
    return 0
  fi
  local labels=()
  local has_self=0
  local p skip existing
  local -a parts
  IFS=',' read -ra parts <<< "$raw"
  for p in "${parts[@]}"; do
    p="$(echo "$p" | xargs)"
    [[ -z "$p" ]] && continue
    if [[ ! "$p" =~ ^[A-Za-z0-9._-]+$ ]]; then
      echo "ERROR: invalid runner label: $p" >&2
      exit 1
    fi
    if [[ "$p" == "self-hosted" ]]; then
      has_self=1
    fi
    skip=0
    for existing in "${labels[@]+"${labels[@]}"}"; do
      if [[ "$existing" == "$p" ]]; then skip=1; break; fi
    done
    [[ "$skip" == "1" ]] && continue
    labels+=("$p")
  done
  if [[ "${#labels[@]}" -eq 0 ]]; then
    emit_hosted
    return 0
  fi
  local json
  json="$(printf '%s\n' "${labels[@]}" | jq -R . | jq -s -c .)"
  if [[ "$has_self" != "1" ]]; then
    json="$(echo "$json" | jq -c '["self-hosted"] + .')"
  fi
  echo "runs_on=${json}" >> "$GITHUB_OUTPUT"
  echo "max_parallel=1" >> "$GITHUB_OUTPUT"
  echo "self_hosted=true" >> "$GITHUB_OUTPUT"
  echo "Runner labels: ${json}"
}

# Keep only PR labels that are GHA runner labels (not issue taxonomy).
pr_runner_csv() {
  local raw p lp keep
  raw="${PR_LABELS:-}"
  raw="${raw//;/,}"
  local out=()
  local -a parts
  IFS=',' read -ra parts <<< "$raw"
  for p in "${parts[@]}"; do
    p="$(echo "$p" | xargs)"
    [[ -z "$p" ]] && continue
    lp="$(printf '%s' "$p" | tr '[:upper:]' '[:lower:]')"
    keep=0
    case "$lp" in
      self-hosted|windows|linux|macos|x64|arm64|arm) keep=1 ;;
    esac
    if [[ "$keep" == "1" ]]; then
      out+=("$p")
    fi
  done
  if [[ "${#out[@]}" -eq 0 ]]; then
    return 1
  fi
  local joined="${out[0]}"
  local i
  for ((i = 1; i < ${#out[@]}; i++)); do
    joined+=",${out[i]}"
  done
  printf '%s' "$joined"
}

case "${GITHUB_EVENT_NAME}" in
  workflow_dispatch)
    raw="$(trim "${DISPATCH_LABELS:-}")"
    if [[ -z "$raw" ]]; then
      raw="$(trim "${VAR_LABELS:-}")"
    fi
    emit_from_csv "$raw"
    ;;
  pull_request)
    if csv="$(pr_runner_csv)"; then
      echo "PR runner labels: $csv"
      emit_from_csv "$csv"
    else
      emit_hosted
    fi
    ;;
  *)
    emit_hosted
    ;;
esac

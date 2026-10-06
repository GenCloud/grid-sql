#!/usr/bin/env bash
# Resolve GitHub Actions runs-on JSON from dispatch labels / repo vars.
# Env: DISPATCH_LABELS, VAR_LABELS, GITHUB_EVENT_NAME, GITHUB_OUTPUT
# Outputs: runs_on (JSON array), max_parallel, self_hosted
set -euo pipefail

hosted_json='["ubuntu-latest"]'

emit_hosted() {
  echo "runs_on=${hosted_json}" >> "$GITHUB_OUTPUT"
  echo "max_parallel=4" >> "$GITHUB_OUTPUT"
  echo "self_hosted=false" >> "$GITHUB_OUTPUT"
  echo "Runner: ubuntu-latest (GitHub-hosted)"
}

if [[ "${GITHUB_EVENT_NAME}" != "workflow_dispatch" ]]; then
  emit_hosted
  exit 0
fi

raw="${DISPATCH_LABELS:-}"
raw="${raw#"${raw%%[![:space:]]*}"}"
raw="${raw%"${raw##*[![:space:]]}"}"
if [[ -z "$raw" ]]; then
  raw="${VAR_LABELS:-}"
  raw="${raw#"${raw%%[![:space:]]*}"}"
  raw="${raw%"${raw##*[![:space:]]}"}"
fi
lower="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
if [[ -z "$raw" || "$lower" == "github" || "$lower" == "ubuntu-latest" || "$lower" == "hosted" ]]; then
  emit_hosted
  exit 0
fi

raw="${raw//;/,}"
labels=()
has_self=0
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
  exit 0
fi
json="$(printf '%s\n' "${labels[@]}" | jq -R . | jq -s -c .)"
if [[ "$has_self" != "1" ]]; then
  json="$(echo "$json" | jq -c '["self-hosted"] + .')"
fi
echo "runs_on=${json}" >> "$GITHUB_OUTPUT"
echo "max_parallel=1" >> "$GITHUB_OUTPUT"
echo "self_hosted=true" >> "$GITHUB_OUTPUT"
echo "Runner labels: ${json}"

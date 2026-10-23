#!/usr/bin/env bash
# Resolve org.genfork:grid-sql-client version and sync Jepsen Leiningen deps.
# Usage:
#   source .../jepsen-client-version.sh   # defines helpers
#   bash .../jepsen-client-version.sh sync|print
# Never hardcode grid-sql-client version in project.clj / CI — always sync from pom.xml.

jepsen_scripts_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jepsen_default_root="$(cd "$jepsen_scripts_dir/../../.." && pwd)"

jepsen_resolve_grid_sql_version() {
  # Compat alias: historically returned reactor version; now returns client coord.
  jepsen_resolve_grid_sql_client_version "$@"
}

jepsen_resolve_grid_sql_client_version() {
  local root="${1:-$jepsen_default_root}"
  local pom="$root/pom.xml"
  local client_pom="$root/grid-sql-client/pom.xml"
  local ver=""

  if [[ -f "$pom" ]]; then
    ver="$(awk '
      /<grid\.sql\.client\.version>/ {
        sub(/.*<grid\.sql\.client\.version>/, "")
        sub(/<\/grid\.sql\.client\.version>.*/, "")
        gsub(/^[ \t\r\n]+|[ \t\r\n]+$/, "")
        print
        exit
      }
    ' "$pom")"
  fi

  if [[ -z "$ver" && -f "$client_pom" ]]; then
    # First <version> after </parent> (module override), else fail.
    ver="$(awk '
      /<\/parent>/ { after_parent = 1; next }
      after_parent && /<version>/ {
        sub(/.*<version>/, "")
        sub(/<\/version>.*/, "")
        gsub(/^[ \t\r\n]+|[ \t\r\n]+$/, "")
        print
        exit
      }
    ' "$client_pom")"
  fi

  if [[ -z "$ver" ]]; then
    echo "ERROR: could not resolve grid-sql-client version (missing grid.sql.client.version in $pom and version in $client_pom)" >&2
    return 1
  fi
  printf '%s\n' "$ver"
}

jepsen_sync_project_clj() {
  local root="${1:-$jepsen_default_root}"
  local ver
  ver="$(jepsen_resolve_grid_sql_client_version "$root")"
  local clj="$root/benchmarks/jepsen/clojure/project.clj"
  if [[ ! -f "$clj" ]]; then
    echo "ERROR: missing $clj" >&2
    return 1
  fi
  local tmp
  tmp="$(mktemp)"
  # Replace only the grid-sql-client coordinate version (any previous value).
  awk -v ver="$ver" '
    {
      if ($0 ~ /org\.genfork\/grid-sql-client/) {
        sub(/org\.genfork\/grid-sql-client "[^"]*"/, "org.genfork/grid-sql-client \"" ver "\"")
      }
      print
    }
  ' "$clj" >"$tmp"
  if ! grep -q "org.genfork/grid-sql-client \"$ver\"" "$tmp"; then
    echo "ERROR: failed to write grid-sql-client \"$ver\" into $clj" >&2
    rm -f "$tmp"
    return 1
  fi
  mv "$tmp" "$clj"
  echo "Synced Jepsen project.clj → org.genfork/grid-sql-client \"$ver\" (grid.sql.client.version / grid-sql-client pom)"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  set -euo pipefail
  cmd="${1:-sync}"
  case "$cmd" in
    print|version)
      jepsen_resolve_grid_sql_client_version "${2:-$jepsen_default_root}"
      ;;
    sync)
      jepsen_sync_project_clj "${2:-$jepsen_default_root}"
      ;;
    *)
      echo "Usage: $0 sync|print [repo-root]" >&2
      exit 2
      ;;
  esac
fi

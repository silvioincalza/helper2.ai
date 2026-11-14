#!/usr/bin/env bash
set -euo pipefail

usage() { echo "usage: env-align.sh [--dry-run] <source.env> <target.env>" >&2; exit 2; }

dry_run=
if [ "${1:-}" = "--dry-run" ]; then dry_run=1; shift; fi
[ $# -eq 2 ] || usage
source_file=$1
target_file=$2
[ -f "$source_file" ] || { echo "no such file: $source_file" >&2; exit 1; }
[ -f "$target_file" ] || { echo "no such file: $target_file" >&2; exit 1; }

missing_lines() {
  awk '
    function key_of(line) {
      if (match(line, /^#[[:space:]]?[A-Za-z_][A-Za-z0-9_]*=/)) { sub(/^#[[:space:]]?/, "", line) }
      else if (!match(line, /^[A-Za-z_][A-Za-z0-9_]*=/)) { return "" }
      return substr(line, 1, index(line, "=") - 1)
    }
    pass == 1 {
      key = key_of($0)
      if (key != "") { known[key] = 1 } else if ($0 ~ /^#/) { known_comment[$0] = 1 }
      next
    }
    /^[[:space:]]*$/ { n = 0; next }
    {
      key = key_of($0)
      if (key == "") { if ($0 ~ /^#/) { pending[++n] = $0 }; next }
      if (!(key in known)) {
        for (i = 1; i <= n; i++) {
          if (!(pending[i] in known_comment)) { print pending[i]; known_comment[pending[i]] = 1 }
        }
        print
        known[key] = 1
      }
      n = 0
    }
  ' pass=1 "$target_file" pass=2 "$source_file"
}

key_names() { sed -nE 's/^#?[[:space:]]?([A-Za-z_][A-Za-z0-9_]*)=.*/\1/p'; }

lines=$(missing_lines)
if [ -z "$lines" ]; then
  echo "$target_file already has every key of $source_file"
  exit 0
fi

count=$(key_names <<< "$lines" | wc -l | tr -d ' ')
if [ -n "$dry_run" ]; then
  echo "would add $count key(s) from $source_file to $target_file:"
  key_names <<< "$lines" | sed 's/^/  /'
  exit 0
fi

[ -z "$(tail -c 1 "$target_file")" ] || echo >> "$target_file"
printf '\n%s\n' "$lines" >> "$target_file"
echo "added $count key(s) from $source_file to $target_file:"
key_names <<< "$lines" | sed 's/^/  /'

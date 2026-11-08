#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../.." && pwd)
WORK=${WORK:-$ROOT/.release-work}
OUTPUT=${1:?Usage: package-dependency-sources.sh /new/output.tar.gz}
[[ ! -e "$OUTPUT" ]] || { echo 'Refusing to overwrite source artifact' >&2; exit 1; }
[[ -s "$WORK/sources/SOURCE-INDEX.json" ]]
# Dependency archives, packaging recipes and notices only; no signing material,
# private caches, APKs, application build trees or host home directories.
tar --sort=name --mtime=@1794096000 --owner=0 --group=0 --numeric-owner \
  --exclude=__pycache__ --exclude='*.pyc' -cf - \
  -C "$WORK" sources -C "$ROOT" scripts/appimage LICENSE THIRD-PARTY-NOTICES.md docs/APPIMAGE.md \
  | gzip -n -1 > "$OUTPUT"
sha256sum "$OUTPUT"

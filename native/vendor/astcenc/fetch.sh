#!/usr/bin/env bash
# Vendors ARM's astc-encoder (the library part only) next to this script. The sources are not
# committed: run this before building native/ (CI does, see [natives.tessera_bridge] ci_prepare).
set -euo pipefail

PINNED_COMMIT="${TESSERA_ASTCENC_COMMIT:-d0f91386350222fcbf6da533c0f7a3f9b5c6b783}"
UPSTREAM_URL="https://github.com/ARM-software/astc-encoder.git"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"

trap 'rm -rf "$WORK_DIR"' EXIT

git clone --quiet "$UPSTREAM_URL" "$WORK_DIR"
git -C "$WORK_DIR" checkout --quiet "$PINNED_COMMIT"

# Everything under Source/ that is the codec: not the command line tool (astcenccli_*), the
# bundled test images / fuzzers / unit tests, or ThirdParty (image loaders only the CLI needs).
rm -f "$SCRIPT_DIR"/astcenc*.cpp "$SCRIPT_DIR"/astcenc*.h
for file in "$WORK_DIR"/Source/astcenc*.cpp "$WORK_DIR"/Source/astcenc*.h; do
    case "$(basename "$file")" in
        astcenccli_*) ;;
        *) cp "$file" "$SCRIPT_DIR/" ;;
    esac
done
cp "$WORK_DIR/LICENSE.txt" "$SCRIPT_DIR/LICENSE"

echo "Vendored astc-encoder@$PINNED_COMMIT into $SCRIPT_DIR"

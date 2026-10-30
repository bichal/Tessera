#!/usr/bin/env bash
# Vendors every third-party source the native bridge compiles (bc7enc_rdo and astc-encoder).
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$DIR/bc7enc_rdo/fetch.sh"
"$DIR/astcenc/fetch.sh"
